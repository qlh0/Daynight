-- 滑动窗口限流
-- KEYS[1] = 限流key
-- ARGV[1] = 当前时间戳(毫秒)
-- ARGV[2] = 窗口长度(毫秒)
-- ARGV[3] = 窗口内允许的最大次数
-- ARGV[4] = 本次请求的唯一标识（用于ZSET成员，避免相同分数被覆盖）
--
-- 返回值：0 表示放行；大于0 表示被限流，值为建议等待的毫秒数

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 把窗口之外的请求记录删掉（这就是"滑动"的含义）
redis.call('zremrangebyscore', key, 0, now - window)

-- 2. 统计窗口内现有请求数
local count = redis.call('zcard', key)

-- 3. 超过阈值就拒绝，并算出最早那次请求还要多久滑出窗口
if count >= limit then
    local oldest = redis.call('zrange', key, 0, 0, 'WITHSCORES')
    if oldest[2] then
        return math.ceil(tonumber(oldest[2]) + window - now)
    end
    return 1
end

-- 4. 放行：记录本次请求，并给整个key设置过期时间，避免冷用户长期占用内存
redis.call('zadd', key, now, member)
redis.call('pexpire', key, window)

return 0
