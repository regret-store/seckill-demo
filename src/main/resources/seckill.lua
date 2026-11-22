-- 1. 接收参数
local productId = KEYS[1]
local userId = ARGV[1]
local bucketIndex = ARGV[2] -- 【新增】桶的索引 (0-9)

--Redis里的Key定义
-- 【修改】动态拼接带桶索引的库存 Key
local stockKey = 'seckill:stock:' .. productId .. ':' .. bucketIndex

local orderKey = 'seckill:order:' ..productId
local startKey = 'seckill:start:' .. productId
local endKey = 'seckill:end:' .. productId

-- 2. 判断活动是否开始或结束
local startTime = redis.call('get', startKey)
local endTime = redis.call('get', endKey)
local now = redis.call('time')[1] * 1000 -- 获取 Redis 当前时间的毫秒数

if startTime and tonumber(now) < tonumber(startTime) then
    return 3 -- 3 表示活动未开始
end
if endTime and tonumber(now) > tonumber(endTime) then
    return 4 -- 4 表示活动已结束
end

-- 3. 判断库存是否充足（如果不存在或者小于等于0，返回 1 表示库存不足）
local stock = redis.call('get',stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1
end

-- 4. 判断用户是否已经抢过（利用 Redis 的 Set 结构，如果存在返回 2 表示重复下单）
if redis.call('sismember',orderKey,userId) == 1 then
    return 2
end

-- 5. 扣减库存，并把用户 ID 存入 Set 集合
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)

-- 6. 返回 0 表示秒杀成功
return 0
