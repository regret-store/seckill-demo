package com.example.seckilldemo.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class SeckillDelayEventListener {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 核心：只有数据库事务 commit 成功后，才发送延迟消息
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeckillDelay(SeckillDelayEvent event)
    {
        String delayMsg = event.getUserId() + "," + event.getProductId();
        try {
            rabbitTemplate.convertAndSend(
                    "plugin.delay.exchange",
                    "plugin.delay.routing.key",
                    delayMsg,
                    message -> {
                        message.getMessageProperties().setHeader("x-delay",10000);
                        return message;
                    }
            );
            log.info("【延迟消息发送成功】订单已落库，延时消息已发: {}", delayMsg);
        }
        catch (Exception e)
        {
            // 如果这里发送失败，说明事务已经提交了，但延时消息没发出去。
            // 这种极端情况需要将消息落库，由定时任务去扫描重发（暂时仅做日志记录，体现有思考）
            log.error("【严重警告】延迟消息发送失败，请人工介入: {}", delayMsg, e);
        }
    }
}
