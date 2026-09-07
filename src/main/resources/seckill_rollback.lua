local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local updatedAt = ARGV[4]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local reservationKey = 'seckill:reservation:v1:' .. orderId
local pendingKey = 'seckill:reservation:pending:v1'

if redis.call('exists', reservationKey) == 0 then
    -- Missing trace is unknown, not a confirmed idempotent refund.
    return -3
end

if redis.call('hget', reservationKey, 'userId') ~= userId or
        redis.call('hget', reservationKey, 'voucherId') ~= voucherId then
    return -1
end

local processStatus = redis.call('hget', reservationKey, 'processStatus')
if processStatus == 'PERSISTED' then
    return -2
end
if processStatus == 'REFUNDED' then
    return 0
end

if redis.call('srem', orderKey, userId) == 1 then
    redis.call('incrby', stockKey, 1)
    redis.call('hset', reservationKey, 'processStatus', 'REFUNDED')
    redis.call('hset', reservationKey, 'updatedAt', updatedAt)
    redis.call('zrem', pendingKey, orderId)
    return 1
end

-- The reservation exists but its user marker is gone. Keep it unresolved.
return -4
