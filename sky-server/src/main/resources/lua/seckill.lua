-- 秒杀预减库存脚本
-- KEYS[1] = seckill:stock:{seckillId}   库存
-- KEYS[2] = seckill:buyers:{seckillId}  已抢购用户集合
-- ARGV[1] = userId

local stock = tonumber(redis.call('get', KEYS[1]))

-- 库存key不存在：活动未预热或已下线
if stock == nil then
    return -1
end

-- 先判断是否重复下单，这样买过的用户提示更准确
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 库存不足
if stock <= 0 then
    return 0
end

-- 扣减库存 + 记录用户，Lua在Redis中原子执行，不会被打断
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[1])

return 1
