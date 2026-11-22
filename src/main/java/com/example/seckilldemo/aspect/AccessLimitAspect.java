package com.example.seckilldemo.aspect;


import com.example.seckilldemo.annotation.AccessLimit;
import com.example.seckilldemo.exception.LimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Aspect    //告诉 Spring："我是一个切面（总机）"
@Component    //把它注册成 Spring 的 Bean，让框架管理它
public class AccessLimitAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 提前加载 Lua 脚本，避免每次读取文件
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT;
    static {
        LIMIT_SCRIPT = new DefaultRedisScript<>();
        LIMIT_SCRIPT.setLocation(new ClassPathResource("limit.lua"));
        LIMIT_SCRIPT.setResultType(Long.class);
    }

    /*
    @Around 环绕通知：在目标方法执行前后都能插手，是最强的一种
    @annotation(...) 切点表达式：只拦截加了 @AccessLimit 注解的方法
    ProceedingJoinPoint 被拦截的那个方法的"现场"，你能从它身上问出：是哪个方法？参数是什么？然后决定放不放行
     */

    @Around("@annotation(com.example.seckilldemo.annotation.AccessLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取当前请求的 HttpServletRequest 对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
            //放行：真正去执行原来的秒杀方法，返回值交回来
        }
        HttpServletRequest request = attributes.getRequest();

        // 【改进一：获取真实 IP】
        String ip = request.getHeader("X-Forwarded-For");
        if(ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)){
            ip = request.getHeader("X-Real-IP");
        }
        if(ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)){
            ip = request.getRemoteAddr();
        }
        // 如果 X-Forwarded-For 包含多个 IP（客户端->代理1->代理2），取第一个
        if(ip != null && ip.contains(",")){
            ip = ip.split(",")[0].trim();
        }

        // 2. 获取方法上的 @AccessLimit 注解，拿到配置的 seconds 和 maxCount
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        AccessLimit accessLimit = method.getAnnotation(AccessLimit.class);
        int seconds = accessLimit.seconds();
        int maxCount = accessLimit.maxCount();

        // 3. 拼接 Redis 的 Key，格式：limit:方法名:IP
        String key = "limit:" + method.getDeclaringClass().getName() + "."+ method.getName() + ":" + ip;

        // 4. 在 Redis 中进行自增计数
        // 【改进二：执行 Lua 脚本完成原子操作】
        Long result = stringRedisTemplate.execute(
                LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(maxCount),
                String.valueOf(seconds)
        );

        // 如果返回 0，代表超限
        if(result==null||result==0)
        {
            // 【改进三：抛出特定的 LimitException，而不是 RuntimeException】
            throw new LimitException("访问过于频繁，请稍后再试！");
        }


        // 7. 放行，执行原来的秒杀业务逻辑
        return joinPoint.proceed();
    }
}

/*
秒杀请求进来
  ↓
总机(AOP)拦下，问：这方法贴了 @AccessLimit 吗？
  ├─ 没贴 → 直接放行
  └─ 贴了 → 继续：
      拿 IP、拿配置（几秒、几次）
      Redis 里 key = "limit:seckill:127.0.0.1" 的值 +1
      如果是第 1 次 → 给这个 key 设个过期时间（比如 1 秒后消失）
      如果加完 > maxCount → 抛异常"请求过于频繁" ❌
      否则 → joinPoint.proceed() 放行，去执行真正的秒杀 ✅
 */
