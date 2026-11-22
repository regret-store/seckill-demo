package com.example.seckilldemo.listener;

import com.example.seckilldemo.service.SeckillService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import com.rabbitmq.client.Channel;

import org.springframework.amqp.core.Message;

@Component
public class SeckillMessageListener {

    @Autowired
    private SeckillService seckillService;

    // 监听我们之前定义的队列
    @RabbitListener(queues = "seckill.queue")
    public void receiveMessage(Message message, Channel channel)throws IOException {

        // 消息的唯一标签
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msgBody = new String(message.getBody());
        System.out.println("收到 MQ 消息：" + msgBody);

        try {
            // 解析消息，格式是 "userId,productId"
            String[] parts = msgBody.split(",");
            Long userId = Long.parseLong(parts[0]);
            Long productId = Long.parseLong(parts[1]);

            // 直接调用 Service 层处理业务（外部 Bean 调用，强制走代理，事务生效）
            seckillService.processSeckill(userId, productId);

            // 业务处理成功，手动 ACK 告诉 MQ：这条消息我搞定了，你可以删了
            // 参数二：false 表示只确认当前这一条消息
            channel.basicAck(deliveryTag,false);

        } catch (Exception e) {

            System.err.println("消息处理失败: " + e.getMessage());

            // 处理失败，拒绝该消息，并让它重新进入队列（或者进入死信队列）
            // 参数二：false 表示只拒绝当前这条
            // 参数三：true 表示重新入队（注意：如果是因为业务代码 bug 一直报错，这会导致死循环，生产环境会配置死信队列）
            channel.basicNack(deliveryTag,false,true);


        }
    }
}