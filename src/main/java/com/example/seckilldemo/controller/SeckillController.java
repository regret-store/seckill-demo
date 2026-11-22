package com.example.seckilldemo.controller;

import com.example.seckilldemo.annotation.AccessLimit;
import com.example.seckilldemo.common.Result;
import com.example.seckilldemo.common.ResultCode;
import com.example.seckilldemo.entity.Product;
import com.example.seckilldemo.entity.SeckillOrder;
import com.example.seckilldemo.mapper.ProductMapper;
import com.example.seckilldemo.mapper.SeckillOrderMapper;
import com.example.seckilldemo.service.LocalMessageService;
import com.example.seckilldemo.service.SeckillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private LocalMessageService localMessageService;

    @Autowired
    private SeckillService seckillService;

    // 提前加载好 Lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 为了模拟真实的网络和业务耗时，我们故意加上休眠，不然数据库跑太快看不出并发问题
    // 1秒内最多只能请求3次
    //http://localhost:8080/seckill/test?productId=1&userId=1001
    @GetMapping("/test")
    //@AccessLimit(seconds = 1, maxCount = 3) // 压测时记得注释掉
    public Result<String> seckill(@RequestParam Long productId, @RequestParam Long userId)throws Exception {

            // 【新增】计算桶索引 (0-9)
            int bucketCount = 10;
            int bucketIndex = (int) (userId % bucketCount);

            // 执行 Lua 脚本，多传一个 bucketIndex 参数
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.singletonList(productId.toString()),
                    userId.toString(),
                    String.valueOf(bucketIndex) // <--- 传参
            );

            if (result == null) return Result.error(ResultCode.SYSTEM_ERROR);
            if (result == 1) return Result.error(ResultCode.STOCK_EMPTY);
            if (result == 2) return Result.error(ResultCode.REPEAT_BUY);
            if (result == 3) return Result.error(ResultCode.SECKILL_FAIL, "活动还未开始！");
            if (result == 4) return Result.error(ResultCode.SECKILL_FAIL, "活动已结束！");

            // ... 后面调用 localMessageService 发送 MQ 的逻辑不变 ...
            localMessageService.saveLocalMessage(userId, productId);
            return Result.success("秒杀成功，排队中！");
    }


    @GetMapping("/preheat")
    public Result<String> preheat(@RequestParam Long productId) throws Exception{
        Product product = productMapper.selectById(productId);
        if (product != null) {
            int totalStock = product.getStock();
            int bucketCount = 10; // 分成 10 个桶
            int stockPerBucket = totalStock / bucketCount; // 每个桶的库存

            for (int i = 0; i < bucketCount; i++) {
                // Key 格式：seckill:stock:1:0, seckill:stock:1:1 ...
                String bucketKey = "seckill:stock:" + productId + ":" + i;
                stringRedisTemplate.opsForValue().set(bucketKey, String.valueOf(stockPerBucket));
            }

            // 存入活动时间逻辑保持不变...
            long now = System.currentTimeMillis();
            stringRedisTemplate.opsForValue().set("seckill:start:" + productId, String.valueOf(now - 10000));
            stringRedisTemplate.opsForValue().set("seckill:end:" + productId, String.valueOf(now + 600000));

            return Result.success("预热成功，总库存：" + totalStock + "，已拆分为 10 个桶，每桶：" + stockPerBucket);
        }
        return Result.error(ResultCode.PRODUCT_NOT_FOUND);
    }

    @PostMapping("/pay/{orderId}")
    public Result<String> pay(@PathVariable Long orderId)throws Exception {
        String resMsg = seckillService.payCallback(orderId);
        return Result.success(resMsg);
    }

}