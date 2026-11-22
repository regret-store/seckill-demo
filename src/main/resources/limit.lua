-- 接收参数

-- Redis 专门传 key 的数组，[1] 是 Lua 的第一个（Lua 下标从 1开始，不是 0！）
local key = KEYS[1]

-- 传普通参数（非 key）的数组，同样从 1 开始,把传进来的字符串转成数字，不然没法比大小
local maxCount = tonumber(ARGV[1])
local seconds = tonumber(ARGV[2])

-- 为什么 key 和参数要分开？因为 Redis 集群要根据 KEYS 决定数据存哪个节点，所以 key 必须走 KEYS，不能混在 ARGV 里

-- 1. 原子性自增
-- 在 Lua 里调用 Redis 命令。等价于你在 redis-cli 敲 INCR key。key 不存在就当 0，加完返回 1；存在就 +1 返回新值
local currentCount = redis.call('incr',key)

-- 2. 如果是第一次访问，直接设置过期时间
if currentCount == 1 then
    redis.call('expire',key,seconds)
end

-- 3. 如果超过了最大限制，直接返回 0 (代表被限流)
if currentCount > maxCount then
    return 0
else
    return 1
end