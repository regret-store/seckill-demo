package com.example.seckilldemo.listener;

import com.example.seckilldemo.service.SeckillService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DeadMessageListener {

    @Autowired
    private SeckillService seckillService;

    // 监听死信队列
    @RabbitListener(queues = "plugin.delay.queue")
    public void receiveDeadMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msgBody = new String(message.getBody(), StandardCharsets.UTF_8);

        // 打印具体是哪一秒收到的
        System.out.println("【死信到达时间】" + java.time.LocalTime.now() + " 收到：" + msgBody);

        try {
            String[] parts = msgBody.split(",");
            Long userId = Long.parseLong(parts[0]);
            Long productId = Long.parseLong(parts[1]);

            // 调用 Service 取消订单并回滚
            seckillService.cancelOrderAndRollbackStock(userId, productId);

            // 处理成功，ACK
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            System.err.println("死信处理失败：" + e.getMessage());
            // 死信处理失败，拒绝且不重回队列（因为已经是死信了，再回去就死循环了，真实场景通常记日志报警人工介入）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}