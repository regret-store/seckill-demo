package com.example.seckilldemo.service.impl;

import com.example.seckilldemo.entity.SeckillOrder;
import com.example.seckilldemo.event.SeckillDelayEvent;
import com.example.seckilldemo.mapper.ProductMapper;
import com.example.seckilldemo.mapper.SeckillOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 纯单元测试：不启动 Spring、不连 MySQL/Redis/RabbitMQ，毫秒级跑完。 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock ProductMapper productMapper;
    @Mock SeckillOrderMapper orderMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks SeckillServiceImpl seckillService;

    @BeforeEach
    void stubRedisOps() {
        // lenient()：有些分支走不到 opsForValue()，不加 lenient 会报 UnnecessaryStubbingException
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ==================== processSeckill ====================

    @Test
    @DisplayName("Redis 防重 Key 已存在 → 直接丢弃，绝不扣数据库库存")
    void duplicateMessageShouldNotTouchDatabase() {
        when(valueOps.setIfAbsent("order:repeat:1001:1", "1", 10, TimeUnit.MINUTES)).thenReturn(false);

        seckillService.processSeckill(1001L, 1L);

        verify(productMapper, never()).deductStock(anyLong());
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("MySQL 库存不足 → 抛异常触发回滚，并释放防重 Key（否则用户永远买不了）")
    void stockShortShouldThrowAndReleaseRepeatKey() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(productMapper.deductStock(1L)).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.processSeckill(1001L, 1L));

        assertTrue(ex.getMessage().contains("库存不足"));
        verify(redisTemplate).delete("order:repeat:1001:1");
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
    }

    @Test
    @DisplayName("唯一索引兜底命中 → 当正常业务吞掉，不抛异常、不发延迟消息（避免 MQ 死循环重试）")
    void duplicateKeyShouldBeSwallowed() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(productMapper.deductStock(1L)).thenReturn(1);
        when(orderMapper.insert(any(SeckillOrder.class))).thenThrow(new DuplicateKeyException("uk_user_product"));

        assertDoesNotThrow(() -> seckillService.processSeckill(1001L, 1L));

        verify(eventPublisher, never()).publishEvent(any(SeckillDelayEvent.class));
    }

    @Test
    @DisplayName("下单成功 → 订单落库字段正确，且发布延迟事件")
    void successPathShouldPersistOrderAndPublishDelayEvent() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(productMapper.deductStock(1L)).thenReturn(1);

        seckillService.processSeckill(1001L, 1L);

        ArgumentCaptor<SeckillOrder> captor = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(orderMapper).insert(captor.capture());
        SeckillOrder saved = captor.getValue();

        assertAll("落库字段",
                () -> assertEquals(1001L, saved.getUserId()),
                () -> assertEquals(1L, saved.getProductId()),
                () -> assertEquals(0, saved.getStatus(), "新订单必须是待支付"),
                () -> assertNotNull(saved.getCreateTime())
        );
        verify(eventPublisher).publishEvent(any(SeckillDelayEvent.class));
    }

    @Test
    @DisplayName("事务回滚补偿 → afterCompletion(ROLLED_BACK) 时删掉防重 Key")
    void repeatKeyShouldBeReleasedWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();   // 手动激活事务同步，模拟在事务里
        try {
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
            when(productMapper.deductStock(1L)).thenReturn(1);

            seckillService.processSeckill(1001L, 1L);

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size(), "必须注册且只注册一个回滚回调");

            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(redisTemplate).delete("order:repeat:1001:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== payCallback 状态机 ====================

    @Test
    @DisplayName("支付回调：订单不存在")
    void payCallbackOrderNotFound() {
        when(orderMapper.selectById(99L)).thenReturn(null);
        assertEquals("订单不存在", seckillService.payCallback(99L));
        verify(orderMapper, never()).updateById(any(SeckillOrder.class));
    }

    @Test
    @DisplayName("支付回调：已支付订单重复回调 → 幂等返回，不再更新")
    void payCallbackIsIdempotent() {
        when(orderMapper.selectById(6L)).thenReturn(order(6L, 1));

        assertEquals("支付成功（幂等）", seckillService.payCallback(6L));
        verify(orderMapper, never()).updateById(any(SeckillOrder.class));
    }

    @Test
    @DisplayName("支付回调：订单已取消却又收到钱 → 走退款分支")
    void payCallbackOnCancelledOrderTriggersRefund() {
        when(orderMapper.selectById(6L)).thenReturn(order(6L, 2));
        assertEquals("订单已取消，触发退款", seckillService.payCallback(6L));
    }

    @Test
    @DisplayName("支付回调：乐观锁失败（超时任务抢先改了状态）→ 返回失败，不能覆盖")
    void payCallbackOptimisticLockFailure() {
        when(orderMapper.selectById(6L)).thenReturn(order(6L, 0));
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(0);

        assertEquals("支付失败，订单状态已变更", seckillService.payCallback(6L));
    }

    @Test
    @DisplayName("支付回调：待支付 → 已支付")
    void payCallbackSuccess() {
        when(orderMapper.selectById(6L)).thenReturn(order(6L, 0));
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(1);

        assertEquals("支付成功", seckillService.payCallback(6L));

        ArgumentCaptor<SeckillOrder> captor = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
    }

    // ==================== 超时取消 ====================

    @Test
    @DisplayName("核心不变量：乐观锁更新失败时，绝对不能回滚库存（否则一边支付一边加库存）")
    void cancelMustNotRollbackStockWhenOptimisticLockFails() {
        when(orderMapper.selectOne(any())).thenReturn(order(6L, 0));
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(0);

        seckillService.cancelOrderAndRollbackStock(1001L, 1L);

        verify(productMapper, never()).recoverStock(anyLong());
        verify(valueOps, never()).increment(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("取消成功 → 库存退回该用户对应的分桶（1001 % 10 = 1）")
    void cancelShouldReturnStockToCorrectBucket() {
        when(orderMapper.selectOne(any())).thenReturn(order(6L, 0));
        when(orderMapper.updateById(any(SeckillOrder.class))).thenReturn(1);

        seckillService.cancelOrderAndRollbackStock(1001L, 1L);

        verify(productMapper).recoverStock(1L);
        verify(valueOps).increment("seckill:stock:1:1");   // 分桶写错就是超卖，必须断言具体 Key
        verify(redisTemplate).delete("order:repeat:1001:1");
    }

    @Test
    @DisplayName("订单已支付（status=1）→ 超时任务什么都不做")
    void cancelShouldSkipPaidOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order(6L, 1));

        seckillService.cancelOrderAndRollbackStock(1001L, 1L);

        verify(orderMapper, never()).updateById(any(SeckillOrder.class));
        verify(productMapper, never()).recoverStock(anyLong());
    }

    private SeckillOrder order(Long id, int status) {
        SeckillOrder o = new SeckillOrder();
        o.setId(id);
        o.setUserId(1001L);
        o.setProductId(1L);
        o.setStatus(status);
        o.setVersion(0);
        return o;
    }
}