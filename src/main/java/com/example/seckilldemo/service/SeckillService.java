package com.example.seckilldemo.service;

public interface SeckillService {
    // 处理秒杀的核心业务逻辑
    void processSeckill(Long userId, Long productId);

    // 取消订单并回滚
    void cancelOrderAndRollbackStock(Long userId, Long productId);

    void sendBlockingTestMsg();

    // 支付回调
    String payCallback(Long orderId);
}