# seckill-demo 秒杀系统

一个用 Spring Boot 3 写的秒杀 demo，核心目标是**高并发下不超卖、不少卖，并且订单/库存最终一致**。
流量按「Redis 原子预扣 → 本地消息表 → MQ 异步下单 → 延迟消息超时回滚 → 定时对账兜底」的链路走，MySQL 只承担最终落库。

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Java / Spring Boot | 17 / 3.0.2 | Web、事务、事件、AOP |
| MySQL + MyBatis-Plus | connector-j 8.x / 3.5.5 | 订单与库存落库、乐观锁拦截器 |
| Redis（Docker `redis:7.2`） | 7.2.x | Lua 原子预扣、防重、缓存 |
| Redisson | 3.30.0 | 商品缓存互斥锁（防击穿） |
| RabbitMQ + delayed-message 插件 | 4.2.0 | 异步下单、10s 超时取消 |

## 请求链路

```
用户请求 GET /seckill/test
   │
   ├─（可选）AOP @AccessLimit + limit.lua  ── 按 IP 滑动窗口限流，超限返回 429
   │
   ├─ seckill.lua（单命令原子执行）
   │     活动时间校验 → 分桶库存判断 → SISMEMBER 判重 → DECR + SADD
   │     返回 0 成功 / 1 售罄 / 2 重复 / 3 未开始 / 4 已结束
   │
   ├─ 本地消息表 local_message 落库（status=0，与业务同事务）
   │     └─ @TransactionalEventListener(AFTER_COMMIT) 事务提交后才发 MQ
   │
   ├─ MQ seckill.queue → SeckillMessageListener（手动 ACK）
   │     └─ processSeckill：SETNX 防重 → deductStock(stock>0) → insert 订单
   │        唯一索引冲突 / 事务回滚补偿 / 失败 nack 重入队
   │
   ├─ 订单落库后再发 plugin.delay.queue（x-delay=10s）
   │     └─ 到期未支付 → cancelOrderAndRollbackStock：乐观锁改状态 → 回补 MySQL + Redis 分桶
   │
   └─ 三个定时任务兜底：10s 重发失败消息 / 30s 死信对账 / 60s 清理 Redis 幽灵扣减
```

## 核心设计点

- **原子预扣**：判时间、判库存、判重复、扣减、记录五步全在一段 Lua 里完成，不存在「查完再扣」的竞态；`DECR` 只在库存 > 0 时执行，超卖在这层就被掐死。
- **库存分桶**：`seckill:stock:{商品}:{0..9}`，按 `userId % 10` 路由，把单个 key 的热点写压力摊到 10 个 key 上；预热带入、回滚时按同一个桶号回补。
- **本地消息表 + 事务事件**：扣减结果先落 `local_message`，MQ 只在 `AFTER_COMMIT` 发，避免「消息发出去了、事务回滚了」；发送失败由定时任务按 `retry_count` 重投，3 次转人工。
- **多路幂等**：Redis `order:repeat:{uid}:{pid}` SETNX（事务回滚时通过 `TransactionSynchronization` 主动删除补偿）+ `seckill:order` Set 判重 + MySQL `uk_user_product` 唯一索引兜底，重复消息只会打印日志，不会重复扣库存。
- **状态机 + 乐观锁**：支付回调与超时取消并发时，`@Version` 保证只有一方更新成功；`updateById == 0` 的一方必须立刻 return，绝不回滚库存。
- **一致性兜底**：`reconcileGhostStock` 比对 Redis 已购 Set 与 MySQL 订单，只清理「Redis 扣了但库里没单」的幽灵数据，且必须以 `SREM` 返回值 > 0 作为补偿前提。
- **缓存三连**：空值缓存防穿透、Redisson 互斥锁 + double check 防击穿、随机 TTL（30min ± 5min）防雪崩。

## 快速开始

### 1. 起中间件

```bash
# Redis（宿主机端口 6378，具名卷持久化；加 127.0.0.1 前缀可避免暴露到局域网）
docker run -d --name seckill-redis -p 127.0.0.1:6378:6379 \
  -v seckill-redis-data:/data redis:7.2 --appendonly yes

# RabbitMQ（必须带 delayed-message 插件，否则 x-delayed-message 交换机声明失败）
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
  wdzag/rabbitmq-delayed:4.2.0
```

MySQL 本地安装，端口 3306。

### 2. 建库建表

字段与 `entity` 包下的实体一一对应，`uk_user_product` 唯一索引和 `version` 列是业务逻辑正常工作的前提，不能省。

```sql
CREATE DATABASE IF NOT EXISTS seckill_demo DEFAULT CHARSET utf8mb4;
USE seckill_demo;

CREATE TABLE product (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(128) NOT NULL,
  stock       INT NOT NULL DEFAULT 0,
  price       DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE seckill_order (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  product_id  BIGINT NOT NULL,
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
  version     INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_product (user_id, product_id)
);

CREATE TABLE local_message (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  product_id  BIGINT NOT NULL,
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2失败可重试 3死信 4已对账',
  retry_count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_retry (status, retry_count)
);

INSERT INTO product (id, name, stock, price) VALUES (1, 'iPhone 17', 100, 4999.00);
```

### 3. 填数据库密码

`application.yml` 里是 `password: ${DB_PASSWORD}`，不含任何真实凭据。二选一：

- 复制模板文件（目前放在 `src/main/resources/static/application-local.yml.example`）为 `src/main/resources/application-local.yml` 填入密码，启动参数加 `--spring.profiles.active=local`（该文件已在 `.gitignore` 中）；
- 或直接设置环境变量 `DB_PASSWORD`。

Redis / RabbitMQ 的连接信息在 `application.yml`，Redisson 的地址目前硬编码在 `config/RedissonConfig.java`，改端口时两处都要改。

### 4. 启动并预热

```bash
mvn spring-boot:run          # 或 IDEA 直接运行 SeckillDemoApplication
```

秒杀前必须先预热，把 MySQL 库存拆成 10 桶写进 Redis，同时写入活动起止时间：

```
GET http://localhost:8080/seckill/preheat?productId=1
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/seckill/preheat?productId=1` | 库存分桶预热 + 活动时间写入 Redis |
| GET | `/seckill/test?productId=1&userId=1001` | 秒杀入口，返回「排队中」即预扣成功 |
| POST | `/seckill/pay/{orderId}` | 支付回调，状态机 + 乐观锁 |
| GET | `/product/{id}` | 商品详情，含穿透/击穿防护演示 |

业务码：`200` 成功、`429` 限流、`5001` 秒杀失败（含活动未开始/已结束）、`5002` 库存不足、`5003` 重复下单、`500` 系统异常、`5004` 商品不存在。

`@AccessLimit` 目前按压测需要注释掉了，单接口限流打开方式：在 `SeckillController#seckill` 上加 `@AccessLimit(seconds = 1, maxCount = 3)`。

## Redis Key

| Key | 类型 | 含义 |
| --- | --- | --- |
| `seckill:stock:{pid}:{bucket}` | String | 分桶剩余库存 |
| `seckill:order:{pid}` | Set | 已预扣用户集合，判重用 |
| `seckill:start:{pid}` / `seckill:end:{pid}` | String | 活动起止毫秒时间戳 |
| `order:repeat:{uid}:{pid}` | String | 消费侧幂等锁，10 分钟 TTL |
| `limit:{类全名.方法名}:{ip}` | String | 限流计数窗口 |
| `product:cache:{id}` / `lock:product:{id}` | String / Redisson | 商品缓存与互斥锁 |

## 测试

```bash
mvn test                     # 单测 + Lua 脚本测试；SeckillTest 是早期手工压测脚本，需要全套中间件
```

`mvn test` 目前会连带跑 `SeckillTest` 和 `SeckillDemoApplicationTests`（都是 `@SpringBootTest`，要 MySQL / Redis / RabbitMQ 全部在线才能起上下文）。如果只想要一套「clone 下来就能跑绿」的测试，建议给 `SeckillTest` 加 `@Disabled` 或把它一起加进 surefire 的 excludes。

`pom.xml` 里 surefire 已排除 `**/*IT.java`，因为 `SeckillNoOversellIT` 会真正起一个随机端口的服务端打 HTTP，需要 MySQL + Redis + RabbitMQ 全套在线。最稳的跑法是 IDEA 里直接右键运行；命令行想跑就先临时注释掉那行 `<exclude>`，再确认控制台真的输出了 `SeckillNoOversellIT` 的统计（`-Dtest=` 与 `<excludes>` 的优先级在不同 surefire 版本上行为不一致，别假设它一定能绕过）。

| 测试类 | 覆盖点 |
| --- | --- |
| `SeckillServiceImplTest` | 纯 Mockito：重复消费拦截、库存不足抛异常并释放防重 Key、唯一索引冲突不外抛、事务回滚补偿、支付 5 个状态分支、乐观锁失败禁止回滚库存、分桶 key 拼接 |
| `LocalMessageServiceTest` | 消息表状态流转、重试 3 次转死信、`SREM == 0` 时禁止补偿 |
| `AccessLimitAspectTest` | 放行 / 拦截 / Lua 返回 null 时 fail-close、`X-Forwarded-For` 多级代理取首个 IP |
| `SeckillLuaScriptTest` | 真连 Redis 跑 `seckill.lua`：成功 / 判重 / 售罄 / 时间窗 / 桶独立 / 并发不超卖 |
| `SeckillControllerTest` | `@WebMvcTest`：Lua 返回值到业务码的映射、分桶索引传参正确性 |
| `SeckillNoOversellIT` | 500 并发打真实 HTTP：`成功数 == 100 == 库存`、售罄 400、剩余库存 0，并输出 QPS |

其中 `SeckillLuaScriptTest` 需要本地 Redis（6378）在线，其余 Mockito / `@WebMvcTest` 用例不依赖任何中间件。

## 目录结构

```
src/main/java/com/example/seckilldemo
├── annotation / aspect     @AccessLimit + 限流切面（limit.lua）
├── common                  Result / ResultCode 统一响应
├── config                  MyBatis-Plus 乐观锁、RabbitMQ 拓扑、Redisson
├── controller              秒杀入口、预热、支付、商品缓存
├── entity / mapper         product、seckill_order、local_message
├── event                   事务事件与 AFTER_COMMIT 监听器
├── listener                秒杀下单、延迟超时取消
├── service                 核心业务 + 本地消息表补偿
src/main/resources
├── seckill.lua / limit.lua 原子预扣与限流脚本
└── application.yml         中间件配置（密码走环境变量）
```

## 已知局限

- 分桶库存不互通：某个桶先售罄而其它桶仍有余量时，会误判为整体售罄；真实场景需要按桶余量二次路由。
- 时间判断依赖 `redis.call('time')`（容器时钟）而预热写入用宿主机 `System.currentTimeMillis()`，Docker Desktop 下两者存在约 100ms 偏移，压测断言需要留足余量。
- `x-delay` 精度受插件单节点实现限制，不保证严格按时投递；生产上更适合用死信 TTL 或时间轮。
- 单机 Redis / 单节点 MQ，未涉及 Redis 集群下 Lua 的跨 slot 限制、MQ 镜像队列等问题。
- 未接入用户体系与真实支付，`userId` 直接由请求参数传入，仅用于演示。
