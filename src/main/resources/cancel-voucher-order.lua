-- KEYS: 库存、购买用户集合、用户预占订单Hash、补偿完成标记、Outbox
-- ARGV: 用户ID、订单ID、Outbox成员
if redis.call('EXISTS', KEYS[4]) == 1 then
    return 1
end
if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then
    return -1
end
local stock = redis.call('GET', KEYS[1])
if not stock or not string.match(stock, '^%d+$') then
    return -2
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) ~= 1 then
    return -3
end
local outboxType = redis.call('TYPE', KEYS[5]).ok
if outboxType ~= 'none' and outboxType ~= 'zset' then
    return -4
end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[3])
redis.call('SET', KEYS[4], '1')
return 0
