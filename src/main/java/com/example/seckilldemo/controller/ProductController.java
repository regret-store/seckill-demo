package com.example.seckilldemo.controller;

import com.example.seckilldemo.common.Result;
import com.example.seckilldemo.common.ResultCode;
import com.example.seckilldemo.entity.Product;
import com.example.seckilldemo.mapper.ProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;    // Spring Boot 自带，用来做 JSON 转换

    @Autowired
    private RedissonClient redissonClient;

    // GET 请求：http://localhost:8080/product/1

    @GetMapping("/{id}")
    public Result<String> getProductById(@PathVariable Long id) throws Exception {
        String cacheKey = "product:cache:" + id;

        // 1. 先查 Redis 缓存
        String cachedProduct = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedProduct != null) {
            if (cachedProduct.isEmpty() || "null".equals(cachedProduct)) {
                return Result.error(ResultCode.PRODUCT_NOT_FOUND, "商品不存在（命中空缓存）");
            }
            return Result.success(cachedProduct); // 命中缓存，直接返回
        }

        // 2. 缓存未命中，准备去查库。但必须先拿锁！
        String lockKey = "lock:product:" + id;

        //通过 Redisson 客户端，获取一个名为 lockKey 的锁对象
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试加锁：最多等 3 秒，拿到锁后 10 秒自动释放（Redisson看门狗会自动续期）
            boolean isLock = lock.tryLock(3, TimeUnit.SECONDS);

            if (isLock) {
                // ========= 我是抢到锁的幸运儿 =========

                // 3. 双重检测（Double Check）：防止在刚才抢锁的 3 秒里，别人已经把缓存建好了
                cachedProduct = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cachedProduct != null) {
                    return Result.success(cachedProduct);
                }

                // 4. 真正去查 MySQL
                System.out.println("【抢到锁】查询数据库，商品ID: " + id);
                Product product = productMapper.selectById(id);

                // 5. 处理缓存穿透
                if (product == null) {
                    stringRedisTemplate.opsForValue().set(cacheKey, "null", 2, TimeUnit.MINUTES);
                    return Result.error(ResultCode.PRODUCT_NOT_FOUND, "商品不存在（已写入空缓存）");
                }

                // 6. 数据库查到了，转成 JSON 存入 Redis
                String productJson = objectMapper.writeValueAsString(product);
                long randomExpire = 30 * 60 + (long) (Math.random() * 300);
                stringRedisTemplate.opsForValue().set(cacheKey, productJson, randomExpire, TimeUnit.SECONDS);

                return Result.success(productJson);
            } else {
                // ========= 没抢到锁的倒霉蛋 =========
                // 稍微等一下（50毫秒），别人可能已经把数据写进 Redis 了
                Thread.sleep(50);
                // 再去查一次 Redis
                cachedProduct = stringRedisTemplate.opsForValue().get(cacheKey);
                if(cachedProduct!=null)
                {
                    return Result.success(cachedProduct);  // 拿到了别人刚建好的缓存
                }
                // 如果实在没拿到，再返回繁忙
                return Result.error(ResultCode.SYSTEM_ERROR, "当前访问人数过多，请稍后重试！（获取锁超时）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取商品锁被中断", e);
        } finally {
            // 7. 释放锁：必须判断是不是当前线程持有的锁，防止释放别人的锁
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}