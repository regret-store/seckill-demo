package com.example.seckilldemo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ===== 秒杀核心队列 =====
    public static final String SECKILL_QUEUE = "seckill.queue";
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill.routing.key";

    // ===== 延迟队列（基于插件） =====
    public static final String DELAY_EXCHANGE = "plugin.delay.exchange";
    public static final String DELAY_QUEUE = "plugin.delay.queue";
    public static final String DELAY_ROUTING_KEY = "plugin.delay.routing.key";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // ================= 1. 秒杀核心队列配置 =================
    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true);
    }

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillBinding(Queue seckillQueue) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange()).with(SECKILL_ROUTING_KEY);
    }

    // ================= 2. 延迟队列配置（已删除冲突的 directExchange） =================
    // 延迟交换机（类型为 x-delayed-message）
    @Bean
    public CustomExchange delayExchange() {
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("x-delayed-type", "direct"); // 底层路由模式
        return new CustomExchange(DELAY_EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue delayQueue() {
        return new Queue(DELAY_QUEUE, true);
    }

    @Bean
    public Binding delayBinding(Queue delayQueue, CustomExchange delayExchange) {
        return BindingBuilder.bind(delayQueue).to(delayExchange).with(DELAY_ROUTING_KEY).noargs();
    }

    // ================= 3. 生产者确认回调 =================
    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                System.out.println("[生产者确认] 消息成功到达交换机！ id=" + (correlationData != null ? correlationData.getId() : "null"));
            } else {
                System.err.println("[生产者确认] 消息发送失败！原因：" + cause);
                // 实际生产中，这里应该把消息存入数据库，用定时任务重发
            }
        });
    }
}