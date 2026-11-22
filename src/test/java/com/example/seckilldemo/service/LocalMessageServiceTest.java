package com.example.seckilldemo.service;

import com.example.seckilldemo.entity.LocalMessage;
import com.example.seckilldemo.event.SeckillMessageEvent;
import com.example.seckilldemo.mapper.LocalMessageMapper;
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
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalMessageServiceTest {

    @Mock LocalMessageMapper localMessageMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @Mock SeckillOrderMapper orderMapper;

    @InjectMocks LocalMessageService localMessageService;

    @BeforeEach
    void stub() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("保存本地消息：只落库 + 发事件，方法内绝不发 MQ")
    void saveLocalMessageShouldOnlyPersistAndPublish() {
        localMessageService.saveLocalMessage(1001L, 1L);

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getStatus(), "初始状态必须是待发送");
        assertEquals(0, captor.getValue().getRetryCount());

        verify(eventPublisher).publishEvent(any(SeckillMessageEvent.class));
        verifyNoInteractions(rabbitTemplate);   // 发 MQ 必须留给 AFTER_COMMIT 监听器
    }

    @Test
    @DisplayName("补偿重发成功 → 状态置 1")
    void resendSuccessMarksSent() {
        when(localMessageMapper.selectList(any())).thenReturn(List.of(msg(1L, 0, 0)));

        localMessageService.resendFailedMessages();

        verify(rabbitTemplate).convertAndSend(eq("seckill.exchange"), eq("seckill.routing.key"), eq("1001,1"));
        assertEquals(1, lastUpdated().getStatus());
    }

    @Test
    @DisplayName("补偿重发失败 → retryCount+1、状态 2")
    void resendFailureIncrementsRetry() {
        when(localMessageMapper.selectList(any())).thenReturn(List.of(msg(1L, 2, 0)));
        doThrow(new RuntimeException("MQ down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        localMessageService.resendFailedMessages();

        LocalMessage updated = lastUpdated();
        assertEquals(2, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
    }

    @Test
    @DisplayName("重试到第 3 次 → 状态 3 转人工，不再无限重试")
    void retryExhaustedBecomesDeadLetter() {
        when(localMessageMapper.selectList(any())).thenReturn(List.of(msg(1L, 2, 2)));
        doThrow(new RuntimeException("MQ down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        localMessageService.resendFailedMessages();

        assertEquals(3, lastUpdated().getStatus());
    }

    @Test
    @DisplayName("死信对账：退 Redis 分桶库存 + 删防重 Key + 清理已购 Set")
    void reconcileDeadMessagesShouldCompensateAllThreeKeys() {
        when(localMessageMapper.selectList(any())).thenReturn(List.of(msg(1L, 3, 3)));

        localMessageService.reconcileDeadMessages();   // userId=1001 → 桶 1

        verify(valueOps).increment("seckill:stock:1:1");
        verify(redisTemplate).delete("order:repeat:1001:1");
        verify(setOps).remove("seckill:order:1", "1001");
        assertEquals(4, lastUpdated().getStatus(), "对账完必须标记 4，防止重复补偿");
    }

    @Test
    @DisplayName("幽灵对账：MySQL 有订单 → 不动 Redis")
    void ghostReconcileSkipsUsersWithOrder() {
        when(setOps.members("seckill:order:1")).thenReturn(Set.of("1001"));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        localMessageService.reconcileGhostStock();

        verify(setOps, never()).remove(anyString(), any());
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    @DisplayName("幽灵对账：SREM 返回 0 说明别人刚处理了 → 绝对不能补库存（这是最易错的点）")
    void ghostReconcileMustNotCompensateWhenSremReturnsZero() {
        when(setOps.members("seckill:order:1")).thenReturn(Set.of("1001"));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(setOps.remove("seckill:order:1", "1001")).thenReturn(0L);

        localMessageService.reconcileGhostStock();

        verify(valueOps, never()).increment(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("幽灵对账：SREM 返回 1 → 确认是我们删的，才退库存")
    void ghostReconcileCompensatesWhenSremSucceeds() {
        when(setOps.members("seckill:order:1")).thenReturn(Set.of("1001"));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(setOps.remove("seckill:order:1", "1001")).thenReturn(1L);

        localMessageService.reconcileGhostStock();

        verify(valueOps).increment("seckill:stock:1:1");
        verify(redisTemplate).delete("order:repeat:1001:1");
    }

    private LocalMessage msg(Long id, int status, int retry) {
        LocalMessage m = new LocalMessage();
        m.setId(id);
        m.setUserId(1001L);
        m.setProductId(1L);
        m.setStatus(status);
        m.setRetryCount(retry);
        return m;
    }

    private LocalMessage lastUpdated() {
        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).updateById(captor.capture());
        return captor.getValue();
    }
}