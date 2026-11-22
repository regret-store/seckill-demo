package com.example.seckilldemo.service.impl;

import com.example.seckilldemo.entity.SeckillOrder;
import com.example.seckilldemo.event.SeckillDelayEvent;
import com.example.seckilldemo.mapper.ProductMapper;
import com.example.seckilldemo.mapper.SeckillOrderMapper;
import com.example.seckilldemo.service.SeckillService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;


@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private SeckillOrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // rollbackFor = Exception.class 确保发生任何异常都回滚
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processSeckill(Long userId, Long productId) {

        // ================= 幂等性防重逻辑 =================
        // 构造防重 Key，比如 "order:repeat:用户ID:商品ID"
        String repeatKey = "order:repeat:"+userId+":"+productId;

        // 使用 setIfAbsent (Redis 的 SETNX)：如果 key 不存在则设置成功返回 true，存在返回 false
        // 设置 10 分钟过期时间（只要保证时间大于业务处理时间即可）
        boolean isFirst = stringRedisTemplate.opsForValue().setIfAbsent(repeatKey,"1",10, TimeUnit.MINUTES);

        if(Boolean.FALSE.equals(isFirst))
        {
            // 如果 false，说明已经被处理过了，直接丢弃，不往下执行
            System.out.println("重复消费拦截：用户 " + userId + " 已经处理过该订单");
            return;
        }

        // ================= 新增：注册事务回滚时的补偿机制 =================
        // 如果当前事务最终回滚了，把 Redis 里的 repeatKey 删掉，释放用户
        if(TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if(status == STATUS_ROLLED_BACK)
                    {
                        stringRedisTemplate.delete(repeatKey);
                        System.out.println("【事务回滚补偿】已删除 Redis 防重 Key：" + repeatKey);
                    }
                }
            });
        }


        // =================================================

        // 1. 扣减数据库库存
        int affectedRows = productMapper.deductStock(productId);
        if (affectedRows == 0) {
            // 如果库存扣减失败，别忘把刚才的防重 Key 删掉，否则用户不能再买
            stringRedisTemplate.delete(repeatKey);
            // 如果库存扣减失败，主动抛出异常，触发事务回滚
            throw new RuntimeException("数据库库存不足，扣减失败！");
        }

        // 2. 生成订单
        SeckillOrder order = new SeckillOrder();
        order.setUserId(userId);
        order.setActivityId(1L);
        order.setProductId(productId);
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());

        try {
            orderMapper.insert(order);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 发生唯一索引冲突，说明数据库里已经有这单了。
            // 这是最后一道防线拦截成功，属于正常业务，绝对不能抛出去让 MQ 重试。
            System.out.println("【数据库兜底】唯一索引拦截，用户 " + userId + " 已经买过了，直接丢弃消息。");
            return;
        }

        System.out.println("订单落库成功，用户：" + userId+ "，准备发送延迟消息...");

        // ... 原来 processSeckill 里的发 MQ 代码删掉，替换为：
        eventPublisher.publishEvent(new SeckillDelayEvent(userId,productId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrderAndRollbackStock(Long userId, Long productId) {
        // 1. 查订单（用 MyBatis-Plus 的 LambdaQueryWrapper 查最新的一笔订单）
        SeckillOrder order = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, userId)
                        .eq(SeckillOrder::getProductId, productId)
                        .orderByDesc(SeckillOrder::getId)
                        .last("LIMIT 1")
        );

        // 2. 如果订单不存在，或者状态不是 0（待支付），说明用户已经付过钱了或者已经被取消了，直接返回
        if (order == null || order.getStatus() != 0) {
            System.out.println("订单已支付或不存在，无需回滚。用户：" + userId);
            return;
        }

        // 【新增这行】：模拟查完库后，系统卡顿了一下
        //try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }

        // 3. 修改订单状态为 2（已取消）
        order.setStatus(2);
        int updateRows = orderMapper.updateById(order);
        if (updateRows == 0) {
            System.out.println("【并发冲突】超时任务乐观锁拦截，订单可能刚被支付！不再回滚库存！");
            return; // 极其重要：乐观锁失败，直接 return，千万不能往下走回滚库存的代码！
        }

        // 4. 恢复 MySQL 数据库库存
        productMapper.recoverStock(productId);

        // 5. 恢复 Redis 库存（必须回滚到对应的分桶里）
        int bucketCount = 10;
        int bucketIndex = (int) (userId % bucketCount);
        String stockKey = "seckill:stock:" + productId + ":" + bucketIndex;
        stringRedisTemplate.opsForValue().increment(stockKey);

        // 6. 删除 Redis 中的防重 Key（这样用户如果不小心超时了，还有机会重新抢）
        String repeatKey = "order:repeat:" + userId + ":" + productId;
        stringRedisTemplate.delete(repeatKey);

        System.out.println("订单超时未支付，已取消并回滚库存！用户：" + userId);
    }

    @Override
    public void sendBlockingTestMsg() {
        System.out.println("========== 开始发送测试消息 ==========");

        // 1. 发送第一条：延迟 10 秒
        rabbitTemplate.convertAndSend("plugin.delay.exchange", "plugin.delay.routing.key", "消息A_延迟10秒", message -> {
            message.getMessageProperties().setHeader("x-delay", 10000);
            return message;
        });
        System.out.println("【发送完毕】消息A (10秒)");

        try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }

        // 2. 发送第二条：延迟 2 秒
        rabbitTemplate.convertAndSend("plugin.delay.exchange", "plugin.delay.routing.key", "消息B_延迟2秒", message -> {
            message.getMessageProperties().setHeader("x-delay", 2000);
            return message;
        });
        System.out.println("【发送完毕】消息B (2秒)");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String payCallback(Long orderId)
    {
        // 1. 查订单
        SeckillOrder order = orderMapper.selectById(orderId);
        if(order == null)
        {
            return "订单不存在";
        }

        // 2. 状态机判断（极其重要！）
        if (order.getStatus() == 1) return "支付成功（幂等）";
        if (order.getStatus() == 2) {
            System.out.println("【严重警告】订单已取消但收到支付回调，触发退款！");
            return "订单已取消，触发退款";
        }

        // 【新增这行】：模拟网络延迟
        //try { Thread.sleep(2000); } catch (InterruptedException e) { e.printStackTrace(); }

        // 3. 只有状态是 0（待支付）才更新
        order.setStatus(1);    // 1表示已支付
        // MyBatis-Plus 会自动带上 version 字段进行乐观锁校验：UPDATE ... SET status=1, version=version+1 WHERE id=? AND version=?

        int updateRows = orderMapper.updateById(order);

        if(updateRows==0)
        {
            // 说明在查出来之后，更新之前，别人（比如超时任务）把订单改了！
            System.out.println("【并发冲突】乐观锁拦截，订单状态已被其他线程修改！");
            return "支付失败，订单状态已变更";
        }

        return "支付成功";
    }
}