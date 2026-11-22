package com.example.seckilldemo.aspect;

import com.example.seckilldemo.annotation.AccessLimit;
import com.example.seckilldemo.exception.LimitException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessLimitAspectTest {

    /** 被测注解需要一个真实载体（Controller 上那行是注释掉的） */
    static class DummyController {
        @AccessLimit(seconds = 1, maxCount = 3)
        public String hit() { return "ok"; }
    }

    @Mock StringRedisTemplate redisTemplate;
    @Mock ProceedingJoinPoint joinPoint;
    @Mock MethodSignature signature;

    @InjectMocks AccessLimitAspect aspect;

    private MockHttpServletRequest request;

    @BeforeEach
    void bindRequest() throws Throwable {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Method method = DummyController.class.getMethod("hit");
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(method);
        lenient().when(joinPoint.proceed()).thenReturn("ok");
    }

    @AfterEach
    void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    @DisplayName("未超限 → 放行，业务方法被执行")
    void shouldProceedWhenWithinLimit() throws Throwable {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertEquals("ok", aspect.around(joinPoint));
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("超限（脚本返回 0）→ 抛 LimitException，业务方法一次都不能执行")
    void shouldRejectWhenExceeded() throws Throwable {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThrows(LimitException.class, () -> aspect.around(joinPoint));
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Redis 挂了返回 null → 按超限处理（当前实现是 fail-close，值得知道）")
    void shouldRejectWhenScriptReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);
        assertThrows(LimitException.class, () -> aspect.around(joinPoint));
    }

    @Test
    @DisplayName("Key 要带真实 IP，且 X-Forwarded-For 多级代理只取第一个，注解参数正确传给脚本")
    void shouldUseFirstForwardedIpAndAnnotationArgs() {
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertDoesNotThrow(() -> aspect.around(joinPoint));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), eq("3"), eq("1"));
        assertEquals(1, keys.getValue().size(), "Key 必须走 KEYS 数组，不能塞进 ARGV");
        assertTrue(keys.getValue().get(0).endsWith(":203.0.113.7"), "实际 Key: " + keys.getValue().get(0));
    }

    @Test
    @DisplayName("非 Web 线程（定时任务/MQ 消费线程）调用 → 无 Request 时直接放行，不能 NPE")
    void shouldProceedWhenNoRequestContext() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        assertEquals("ok", aspect.around(joinPoint));
        verifyNoInteractions(redisTemplate);
    }
}