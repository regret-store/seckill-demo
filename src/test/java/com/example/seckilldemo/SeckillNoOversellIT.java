package com.example.seckilldemo;

import com.example.seckilldemo.common.Result;
import com.example.seckilldemo.service.LocalMessageService;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeckillNoOversellIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    @LocalServerPort
    private int port;

    @MockBean
    private LocalMessageService localMessageService;

    /**
     * ★ 核心修复：把 TestRestTemplate 底层的连接实现，
     *   从 JDK 的 HttpURLConnection 换成 Apache HttpClient 5 连接池。
     *   500 个并发请求将复用固定的一批长连接，
     *   不再瞬间建 500 条 TCP，彻底消除 Connection refused。
     */
    @Autowired
    void configureHttpClient(TestRestTemplate restTemplate) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);            // 总连接上限
        cm.setDefaultMaxPerRoute(64);   // ★ 关键：不要设成 500！
        // 设成 64 的意思是——只建 64 条 TCP 长连接，
        // 500 个请求在这 64 条上排队复用（keep-alive），
        // 反而避免了瞬时建连风暴。这正是压测想要的效果。

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10))           // 建连超时
                .setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(30)) // 从池里等连接的超时
                .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(30))          // 等响应超时
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(rc)
                .evictExpiredConnections()
                .build();

        restTemplate.getRestTemplate()
                .setRequestFactory(new HttpComponentsClientHttpRequestFactory(client));
    }

    @BeforeEach
    void resetStock() {
        var keys = redis.keys("seckill:*:1*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        for (int bucket = 0; bucket < 10; bucket++) {
            redis.opsForValue().set("seckill:stock:1:" + bucket, "10");
        }
        long now = System.currentTimeMillis();
        redis.opsForValue().set("seckill:start:1", String.valueOf(now - 60_000));
        redis.opsForValue().set("seckill:end:1", String.valueOf(now + 600_000));
        redis.delete("seckill:order:1");
    }

    @Test
    @DisplayName("冒烟测试：单个请求能否正常打通")
    void smokeTest() {
        String url = "http://localhost:" + port + "/seckill/test?productId=1&userId=99999";
        String raw = rest.getForObject(url, String.class);
        System.out.println("【冒烟】原始响应: " + raw);
        assertNotNull(raw);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("走 HTTP 接口抢 100 库存：成功数恰好 100，接口层也不超卖")
    void httpLayerNeverOversells() throws Exception {
        int requests = 500;

        // 线程数 = 请求数，保证 CyclicBarrier 能凑齐放行，制造真实瞬时并发
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CyclicBarrier barrier = new CyclicBarrier(requests);
        CountDownLatch done = new CountDownLatch(requests);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger repeat = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < requests; i++) {
            final long userId = 20_000L + i;
            pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);

                    String url = "http://localhost:" + port
                            + "/seckill/test?productId=1&userId=" + userId;

                    // 兜底重试：万一仍偶发 refused，仅对网络异常重试，最多 5 次
                    Result body = null;
                    for (int attempt = 1; ; attempt++) {
                        try {
                            ResponseEntity<Result> resp =
                                    rest.getForEntity(url, Result.class);
                            body = resp.getBody();
                            break;
                        } catch (ResourceAccessException e) {
                            if (attempt >= 5) throw e;
                            Thread.sleep(20);
                        }
                    }

                    assertNotNull(body, "接口不能返回空 body");
                    switch (body.getCode()) {
                        case 200 -> success.incrementAndGet();
                        case 5002 -> soldOut.incrementAndGet();
                        case 5003 -> repeat.incrementAndGet();
                        default -> {
                            failed.incrementAndGet();
                            System.err.println("非预期业务码 userId=" + userId
                                    + " code=" + body.getCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    failed.incrementAndGet();
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(60, TimeUnit.SECONDS), "并发请求未在 60s 内收敛");
        long cost = System.currentTimeMillis() - t0;

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        if (!errors.isEmpty()) {
            System.err.println("===== 仍有 " + errors.size() + " 个请求异常，首个堆栈 =====");
            errors.get(0).printStackTrace();
        }
        System.out.printf("统计：成功=%d 售罄=%d 重复=%d 异常=%d%n",
                success.get(), soldOut.get(), repeat.get(), failed.get());
        System.out.printf("全链路：%d 请求 / %d ms ≈ %.0f QPS%n",
                requests, cost, requests * 1000.0 / cost);

        assertAll("HTTP 层不变量",
                () -> assertEquals(0, failed.get(), "有请求抛异常，详见控制台"),
                () -> assertEquals(100, success.get(), "成功数必须恰好等于库存（防超卖铁证）"),
                () -> assertEquals(400, soldOut.get(), "售罄数应为 500-100"),
                () -> assertEquals(0, repeat.get(), "不同用户不应触发判重"),
                () -> assertEquals(requests, success.get() + soldOut.get() + repeat.get(),
                        "有请求被吞了"),
                () -> assertEquals(0, sumRemainingStock(), "Redis 还剩库存说明少卖")
        );
    }

    private int sumRemainingStock() {
        int left = 0;
        for (int bucket = 0; bucket < 10; bucket++) {
            String v = redis.opsForValue().get("seckill:stock:1:" + bucket);
            left += (v == null ? 0 : Integer.parseInt(v));
        }
        return left;
    }
}