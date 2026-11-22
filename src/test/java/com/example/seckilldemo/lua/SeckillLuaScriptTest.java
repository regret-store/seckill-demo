package com.example.seckilldemo.lua;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接对 seckill.lua 做行为测试。
 * 只加载 Redis 自动配置：不需要 MySQL / RabbitMQ，也不会启动定时任务和 Tomcat。
 */
@SpringBootTest(classes = SeckillLuaScriptTest.RedisOnlyConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillLuaScriptTest {

    @Configuration
    @Import(RedisAutoConfiguration.class)
    static class RedisOnlyConfig {
        @Bean
        RedisScript<Long> seckillScript() {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource("seckill.lua"));
            script.setResultType(Long.class);
            return script;
        }
    }

    @Autowired StringRedisTemplate redis;
    @Autowired RedisScript<Long> seckillScript;

    private static final String PID = "1";

    @BeforeEach
    void seed() {
        // 不做 ping 预检查：Redis 没起就让 Lettuce 自己抛，异常信息里会写清楚是连接被拒还是认证失败
        clean();
        long now = System.currentTimeMillis();
        redis.opsForValue().set("seckill:start:" + PID, String.valueOf(now - 60_000));
        redis.opsForValue().set("seckill:end:" + PID, String.valueOf(now + 600_000));
        for (int bucket = 0; bucket < 10; bucket++) {
            redis.opsForValue().set("seckill:stock:" + PID + ":" + bucket, "5");
        }
    }

    @AfterEach
    void clean() {
        List<String> keys = new ArrayList<>();
        keys.add("seckill:order:" + PID);
        keys.add("seckill:start:" + PID);
        keys.add("seckill:end:" + PID);
        for (int bucket = 0; bucket < 10; bucket++) keys.add("seckill:stock:" + PID + ":" + bucket);
        redis.delete(keys);
    }

    private long eval(long userId) {
        int bucket = (int) (userId % 10);           // 和 Controller 里同一套分桶算法
        Long code = redis.execute(seckillScript, Collections.singletonList(PID),
                String.valueOf(userId), String.valueOf(bucket));
        assertNotNull(code, "脚本不能返回 null");
        return code;
    }

    @Test
    @Order(1)
    @DisplayName("抢到 → 返回 0，库存 -1，用户进入已购 Set")
    void successDeductsStockAndRecordsUser() {
        assertEquals(0, eval(1001L));
        assertEquals("4", redis.opsForValue().get("seckill:stock:1:1"));
        assertTrue(redis.opsForSet().isMember("seckill:order:1", "1001"));
    }

    @Test
    @Order(2)
    @DisplayName("同一用户再抢 → 返回 2，且库存不再减少")
    void repeatBuyIsRejectedWithoutExtraDeduction() {
        eval(1001L);
        assertEquals(2, eval(1001L));
        assertEquals("4", redis.opsForValue().get("seckill:stock:1:1"));
    }

    @Test
    @Order(3)
    @DisplayName("库存为 0 / Key 不存在 → 都返回 1")
    void soldOut() {
        redis.opsForValue().set("seckill:stock:1:1", "0");
        assertEquals(1, eval(1001L));

        redis.delete("seckill:stock:1:2");
        assertEquals(1, eval(1002L));
    }

    /** 取 Redis 自己的毫秒时间，和 seckill.lua 里的 redis.call('time') 同源 */
    private static final DefaultRedisScript<Long> REDIS_TIME = new DefaultRedisScript<>(
            "local t = redis.call('time')\n" +
                    "return t[1] * 1000 + math.floor(t[2] / 1000)",
            Long.class);

    @Test
    @Order(4)
    @DisplayName("活动未开始 → 3；已结束 → 4")
    void timeWindow() {
        long redisNow = redis.execute(REDIS_TIME, Collections.<String>emptyList());

        // 余量给 60 秒：宿主机、容器、MySQL 之间的时钟偏移通常就是几十到几百毫秒，1ms 的边界必 flaky
        redis.opsForValue().set("seckill:start:" + PID, String.valueOf(redisNow + 60_000));
        assertEquals(3, eval(1001L), "活动开始时间还没到，应该返回 3");

        redis.opsForValue().set("seckill:start:" + PID, "0");
        redis.opsForValue().set("seckill:end:" + PID, String.valueOf(redisNow - 60_000));
        assertEquals(4, eval(1001L), "活动结束时间已过，应该返回 4");
    }

    @Test
    @Order(5)
    @DisplayName("分桶生效：桶 0 卖空不影响桶 1")
    void bucketsAreIndependent() {
        redis.opsForValue().set("seckill:stock:1:0", "0");
        assertEquals(1, eval(1000L));

        redis.opsForValue().set("seckill:stock:1:1", "3");
        assertEquals(0, eval(1001L));
    }

    @Test
    @Order(6)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("并发防超卖：成功数 == 总库存，剩余库存 == 0，Set 成员数 == 成功数")
    void noOversellUnderConcurrency() throws Exception {
        for (int bucket = 0; bucket < 10; bucket++) {
            redis.opsForValue().set("seckill:stock:1:" + bucket, "10");
        }

        int users = 200;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        List<Throwable> threadErrors = new CopyOnWriteArrayList<>();   // 线程里的异常必须带出来，否则只看得到超时

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(users);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);

        for (int i = 0; i < users; i++) {
            final long userId = 10_000L + i;
            ready.countDown();
            pool.execute(() -> {
                try {
                    start.await();
                    switch ((int) eval(userId)) {
                        case 0 -> success.incrementAndGet();
                        case 1 -> soldOut.incrementAndGet();
                        default -> threadErrors.add(new AssertionError("非预期返回码, userId=" + userId));
                    }
                } catch (Throwable t) {
                    threadErrors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        long t0 = System.nanoTime();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发执行超时");
        long costMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        if (!threadErrors.isEmpty()) {
            fail("线程内出现异常，共 " + threadErrors.size() + " 个，首个: " + threadErrors.get(0), threadErrors.get(0));
        }

        int left = 0;
        for (int bucket = 0; bucket < 10; bucket++) {
            String v = redis.opsForValue().get("seckill:stock:1:" + bucket);
            left += (v == null ? 0 : Integer.parseInt(v));
        }
        final int totalLeft = left;

        assertAll("防超卖不变量",
                () -> assertEquals(100, success.get(), "成功数必须恰好等于库存"),
                () -> assertEquals(100, soldOut.get()),
                () -> assertEquals(0, totalLeft, "桶里还剩库存说明少卖"),
                () -> assertEquals(100L, redis.opsForSet().size("seckill:order:1"), "已购 Set 要和成功数一致")
        );

        System.out.printf("Lua 预减层：%d 请求 / %d ms ≈ %.0f TPS%n", users, costMs, users * 1000.0 / costMs);
    }
}