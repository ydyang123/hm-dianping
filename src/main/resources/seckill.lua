-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- -- 1.3.订单id
local orderId = ARGV[3]
-- -- 1.4. 待发送的消息key
local outboxKey = KEYS[1]
-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
local reservationKey = 'seckill:reservation:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
local stock = redis.call('get', stockKey)
if(stock == false) then
    -- 库存 key 不存在，说明该优惠券的库存未预热到 Redis（或活动不存在），返回3
    return 3
end
if(tonumber(stock) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end

-- -- 3.4.扣库存 incrby stockKey -1
-- redis.call('incrby', stockKey, -1)
-- -- 3.5.下单（保存用户）sadd orderKey userId
-- redis.call('sadd', orderKey, userId)
-- -- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...     Redis的Stream流实现消息队列
-- redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)


-- 在写入前检查待发表的类型，避免扣完库存才发现 ZADD 无法执行
local outboxType = redis.call('TYPE', outboxKey).ok
if outboxType ~= 'none' and outboxType ~= 'zset' then
    return redis.error_reply('Invalid seckill outbox key type')
end

-- 订单 ID 保持字符串，避免 Lua 数值转换损失长整型精度
local pendingOrder = orderId .. ':' .. userId .. ':' .. voucherId
local reservationType = redis.call('TYPE', reservationKey).ok
if reservationType ~= 'none' and reservationType ~= 'hash' then
    return redis.error_reply('Invalid reservation key type')
end
-- 扣减库存
redis.call('INCRBY', stockKey, -1)
-- 下单（保存用户）
redis.call('SADD', orderKey, userId)
-- 只有与订单号匹配的预占才允许补偿。
redis.call('HSET', reservationKey, userId, orderId)
-- 发送消息到队列中
redis.call('ZADD', outboxKey, 0, pendingOrder)

return 0
