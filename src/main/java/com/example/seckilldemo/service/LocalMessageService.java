package com.example.seckilldemo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckilldemo.entity.LocalMessage;
import com.example.seckilldemo.entity.SeckillOrder;
import com.example.seckilldemo.event.SeckillMessageEvent;
import com.example.seckilldemo.mapper.LocalMessageMapper;
import com.example.seckilldemo.mapper.SeckillOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class LocalMessageService {

    @Autowired
    private LocalMessageMapper localMessageMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;    // 注入事件发布器

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillOrderMapper orderMapper;


    /**
     * 核心方法：保存消息到本地表，并发布事件
     * 注意：这个方法绝对不包含发 MQ 的代码！
     */

    // 1. 保存消息到本地表
    @Transactional(rollbackFor = Exception.class)
    public void saveLocalMessage(Long userId, Long productId) {
        LocalMessage msg = new LocalMessage();
        msg.setUserId(userId);
        msg.setProductId(productId);
        msg.setStatus(0); // 待发送
        msg.setRetryCount(0);
        localMessageMapper.insert(msg);

        // 发布 Spring 事件。此时事务还没提交
        eventPublisher.publishEvent(new SeckillMessageEvent(userId, productId));
    }



    /**
     * 兜底定时任务：扫描异常消息并重发
     * 每 10 秒扫一次
     */

    @Scheduled(fixedDelay = 10000)
    public void resendFailedMessages() {
        // 只查状态为 0(待发送) 或 2(发送失败)，且重试次数小于 3 次的消息
        List<LocalMessage> messages = localMessageMapper.selectList(
                new LambdaQueryWrapper<LocalMessage>()
                        .in(LocalMessage::getStatus,0,2)
                        .lt(LocalMessage::getRetryCount,3)
        );

        if(messages.isEmpty())return;

        for (LocalMessage msg : messages) {
            try {
                String msgBody = msg.getUserId() + "," + msg.getProductId();
                rabbitTemplate.convertAndSend("seckill.exchange", "seckill.routing.key", msgBody);

                // 发送成功，更新状态
                msg.setStatus(1);
                localMessageMapper.updateById(msg);
                log.info("【定时补偿】重发本地消息成功: {}", msgBody);
            } catch (Exception e) {
                log.error("【定时补偿】重发失败: {}", e.getMessage());
                // 更新重试次数和状态
                msg.setStatus(2);    // 标记为发送失败
                msg.setRetryCount(msg.getRetryCount()+1);
                if(msg.getRetryCount()>=3){
                    msg.setStatus(3);    // 达到上限，变为死信，需人工介入
                    log.error("【定时补偿】消息重试次数达到上限，转人工处理, id: {}", msg.getId());
                }
                localMessageMapper.updateById(msg);
            }
        }
    }



    /**
     * 定时对账任务：专门处理彻底死信（status = 3）的消息
     * 每 30 秒执行一次
     */

    @Scheduled(fixedDelay = 30000)
    public void reconcileDeadMessages()
    {
        // 查询状态为 3（死信）的消息
        List<LocalMessage> deadMessages = localMessageMapper.selectList(
                new LambdaQueryWrapper<LocalMessage>().eq(LocalMessage::getStatus,3)
        );

        if(deadMessages.isEmpty())return;
        log.info("【对账任务】发现 {} 条死信消息，开始反向补偿...",deadMessages.size());

        for(LocalMessage msg : deadMessages)
        {
            Long userId = msg.getUserId();
            Long productId = msg.getProductId();

            try {
                // 1. 恢复 Redis 的分桶库存
                int bucketCount = 10;
                int bucketIndex = (int)(userId%bucketCount);
                String stockKey = "seckill:stock:"+productId+":"+bucketIndex;
                stringRedisTemplate.opsForValue().increment(stockKey);

                // 2. 释放用户的防重 Key，让他可以重新抢
                String repeatKey = "order:repeat:"+userId+":"+productId;
                stringRedisTemplate.delete(repeatKey);

                // 3. 【极其重要】清理 Lua 脚本里的已购用户 Set 集合
                String orderKey = "seckill:order:"+productId;
                stringRedisTemplate.opsForSet().remove(orderKey,userId.toString());

                // 3. 将本地消息表的状态更新为 4（表示已进行过对账补偿）
                msg.setStatus(4);
                localMessageMapper.updateById(msg);

                log.info("【对账补偿成功】用户 {} 的库存已退回 Redis，已购记录已清除", userId);
            }
            catch (Exception e)
            {
                log.error("【对账补偿失败】用户 {}: {}", userId, e.getMessage());
            }
        }
    }



    /**
     * 全局对账任务：清理“幽灵扣减”
     * 每 60 秒执行一次，专门清理 Redis 有记录但 MySQL 没订单的幽灵数据
     */

    @Scheduled(fixedDelay = 60000)
    public void reconcileGhostStock()
    {
        // 为了演示，我们写死查商品 1 的已购集合
        String orderKey = "seckill:order:1";
        Long productId = 1L;

        // 1. 从 Redis 获取所有预扣成功的用户 ID
        Set<String>redisUserIds = stringRedisTemplate.opsForSet().members(orderKey);

        if(redisUserIds == null || redisUserIds.isEmpty()){
            return;
        }

        log.info("【全局对账】开始比对 Redis 预扣与 MySQL 订单... 当前 Redis 记录数: {}", redisUserIds.size());

        for(String userIdStr:redisUserIds)
        {
            Long userId = Long.parseLong(userIdStr);

            // 2. 去 MySQL 查这个用户到底有没有订单
            Long orderCount = orderMapper.selectCount(
                    new LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getUserId,userId)
                            .eq(SeckillOrder::getProductId,productId)
            );

            // 3. 如果 MySQL 里没订单（说明是幽灵扣减）
            if(orderCount == null || orderCount == 0)
            {
                // 【极其关键】：必须用 SREM，它返回的是成功删除的个数。
                // 如果返回 1，说明确实是我们删的，可以进行后续补偿。
                // 如果返回 0，说明在这个瞬间，用户可能刚下完单或者被其他线程处理了，绝对不能补偿！
                Long removed = stringRedisTemplate.opsForSet().remove(orderKey,userIdStr);

                if(removed!=null&&removed>0)
                {
                    // 4. 恢复 Redis 的分桶库存
                    int bucketCount = 10;
                    int bucketIndex = (int)(userId%bucketCount);
                    String stockKey = "seckill:stock:" + productId + ":" + bucketIndex;
                    stringRedisTemplate.opsForValue().increment(stockKey);

                    // 5. 删除用户的防重 Key
                    String repeatKey = "order:repeat:" + userId + ":" + productId;
                    stringRedisTemplate.delete(repeatKey);

                    log.info("【全局对账补偿】发现幽灵扣减！用户 {} 无订单，已退还库存 {} 并释放令牌", userId, stockKey);
                }
            }
        }
    }
}