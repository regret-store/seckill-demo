package com.example.seckilldemo;

import com.example.seckilldemo.service.SeckillService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Disabled("需要 MySQL + Redis + MQ 环境，本地手工压测用")
@SpringBootTest
public class SeckillTest {
    @Autowired
    private SeckillService seckillService;

    @Test
    public void testConcurrentSeckill() throws InterruptedException {
        int threadCount = 50; // 模拟50个用户同时请求
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // 发令枪
        HttpClient client = HttpClient.newHttpClient();

        for (int i = 1; i <= threadCount; i++) {
            final long userId = i%5;
            executorService.execute(() -> {
                try {
                    latch.await(); // 等待发令枪响
                    // 请求我们写好的天真版接口
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8080/seckill/test?productId=1&userId=" + userId))
                            .GET()
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    // 打印返回结果（成功或库存不足）
                    // System.out.println("用户" + userId + " 结果: " + response.body());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        latch.countDown(); // 发令枪响，50个线程瞬间发出请求
        Thread.sleep(3000); // 等3秒钟让它们跑完
    }

    @Test
    public void testAccessLimit() throws Exception {
        // 清理一下 Redis 里的限流记录，保证测试干净 (你可以用我给的 key 前缀清除)
        // 注意：如果你之前测过，最好去 IDEA 的 Database 面板把 limit:test:* 删掉

        HttpClient client = HttpClient.newHttpClient();

        // 我们连续发 5 次请求，看看第几次会被拦截
        // 配合 Controller 上的 @AccessLimit(seconds = 1, maxCount = 3)
        for (int i = 1; i <= 5; i++) {
            final long userId = 1; // 固定用户，只看限流

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/seckill/test?productId=1&userId=" + userId))
                    .GET()
                    .build();

            // 每次请求前稍微停顿一下，模拟人的点击速度（0.1秒）
            // 注意：总时间要控制在 1 秒内，否则 Redis 的 Key 过期了，就不算超限了
            Thread.sleep(100);

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 【改动】把 HTTP 状态码也打印出来，验证是不是 429
            System.out.println("第 " + i + " 次请求| HTTP状态码: " + response.statusCode() + " |结果: " + response.body());
        }
    }

    @Test
    public void testHighConcurrencyQps() throws InterruptedException {
        // 1. 初始化 1000 个库存（为了方便测大并发，我们直接改库）
        // 这里的代码你可以在 MySQL 里手动执行：UPDATE product SET stock = 1000 WHERE id = 1;
        // 然后调一下预热接口，把 1000 刷进 Redis

        int threadCount = 2000; // 模拟 1000 个并发
        ExecutorService executorService = Executors.newFixedThreadPool(100); // 100个线程去执行
        CountDownLatch startLatch = new CountDownLatch(1); // 发令枪
        CountDownLatch endLatch = new CountDownLatch(threadCount); // 等待所有线程跑完

        HttpClient client = HttpClient.newHttpClient();
        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= threadCount; i++) {
            final long userId = i;
            executorService.execute(() -> {
                try {
                    startLatch.await();
                    // 这里测的是完整的秒杀接口（包含防刷、Lua、MQ发送）
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8080/seckill/test?productId=1&userId=" + userId))
                            .GET()
                            .build();
                    client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 开抢！
        endLatch.await(); // 主线程阻塞，等 1000 个请求全部发完
        long endTime = System.currentTimeMillis();

        // 计算 QPS 和 平均响应时间
        long totalTime = endTime - startTime;
        double qps = (1000.0 / totalTime) * 1000;

        System.out.println("=============== 压测报告 ===============");
        System.out.println("总请求数: " + threadCount);
        System.out.println("总耗时: " + totalTime + " 毫秒");
        System.out.println("QPS (每秒查询率): " + String.format("%.2f", qps));
        System.out.println("========================================");

        // 等待 MQ 消费完
        Thread.sleep(5000);
    }

    @Test
    public void testCacheBreakdown() throws InterruptedException {

        int threadCount = 200; // 模拟 n 个并发
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 发令枪
        CountDownLatch startLatch = new CountDownLatch(1);
        // 终点线：等待所有线程执行完毕
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        HttpClient client = HttpClient.newHttpClient();

        //统计结果
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger busyCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    startLatch.await();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8080/product/999"))
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    String body = response.body();

                    if(body.contains("商品")||body.contains("id")||body.contains("null")){
                        successCount.incrementAndGet();
                    }else if(body.contains("人数过多") ||body.contains("系统繁忙")){
                        busyCount.incrementAndGet();
                    }else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("请求异常: " + e.getMessage());
                }finally {
                    endLatch.countDown();  // 每个线程完成后，终点线减 1
                }
            });
        }

        System.out.println("========== 开始并发查询商品 ==========");

        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // 发令枪响

        // 主线程在这里死等，直到 200 个线程全部跑完
        endLatch.await();

        long endTime = System.currentTimeMillis();

        System.out.println("========== 压测结束 ==========");
        System.out.println("总耗时: " + (endTime - startTime) + " ms");
        System.out.println("成功拿到数据: " + successCount.get());
        System.out.println("系统繁忙被拦截: " + busyCount.get());
        System.out.println("直接报错: " + errorCount.get());

        executorService.shutdown();
    }

    @Test
    public void testConcurrentPayAndCancel() throws InterruptedException {
        Long orderId = 6L; // 替换成你刚才秒杀出来的真实订单ID
        Long userId = 1006L;
        Long productId = 1L;

        CountDownLatch latch = new CountDownLatch(1);

        // 线程1：模拟支付
        new Thread(() -> {
            try {
                latch.await();
                seckillService.payCallback(orderId);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();

        // 线程2：模拟超时取消
        new Thread(() -> {
            try {
                latch.await();
                seckillService.cancelOrderAndRollbackStock(userId, productId);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();

        latch.countDown(); // 同时开火！
        Thread.sleep(2000);
    }
/*
    @Test
    public void testAssertion() {
        int expected = 10;
        int actual = 9; // 你可以改成 9 试试
        Assertions.assertEquals(expected, actual, "完蛋了，数字对不上！");
    }


 */
}
