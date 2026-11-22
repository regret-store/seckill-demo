package com.example.seckilldemo.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckilldemo.entity.LocalMessage;
import com.example.seckilldemo.mapper.LocalMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class SeckillMessageEventListener {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LocalMessageMapper localMessageMapper;

    /**
     * 关键：phase = AFTER_COMMIT，保证只有数据库事务真正 commit 成功后，才执行发 MQ
     */
    //@Async    //和启动类一起加，异步薛峰才会生效
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeckillMessage(SeckillMessageEvent event)
    {
        String msgBody = event.getUserId()+","+event.getProductId();
        try {
            // 真正发消息的地方
            rabbitTemplate.convertAndSend("seckill.exchange","seckill.routing.key",msgBody);

            // 发送成功，更新数据库状态为 1 (已发送)
            // 注意：这里最好根据 userId 和 productId 查出刚才那条记录更新
            LocalMessage msg = localMessageMapper.selectOne(
                    new LambdaQueryWrapper<LocalMessage>()
                            .eq(LocalMessage::getUserId,event.getUserId())
                            .eq(LocalMessage::getProductId,event.getProductId())
                            .orderByDesc(LocalMessage::getId)
                            .last("LIMIT 1")
            );

            if(msg!=null){
                msg.setStatus(1);
                localMessageMapper.updateById(msg);
            }
            log.info("【事件监听】MQ发送成功: {}", msgBody);
        }
        catch (Exception e){
            log.error("【事件监听】MQ发送失败，交给定时任务兜底: {}", e.getMessage());
            // 发送失败什么都不用做，因为消息表里状态还是 0，定时任务会接管
        }
    }
}
