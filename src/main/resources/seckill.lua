-- 1.参数列表
--1.1.优惠券id
local voucherId=ARGV[1]
--1.2.用户id
local userId=ARGV[2]
--1.3.订单id
local orderId=ARGV[3]
--1.4.预扣时间（毫秒）
local reservedAt=ARGV[4]

-- 2.数据key
--2.1.库存key
local stockKey='seckill:stock:' .. voucherId
--2.2.订单key
local orderKey='seckill:order:' .. voucherId
--2.3.订单预扣追踪key
local reservationKey='seckill:reservation:v1:' .. orderId
--2.4.待对账订单索引
local pendingKey='seckill:reservation:pending:v1'

-- 3.脚本业务
--3.1.判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    --print("库存获取失败: " .. stockKey)
    return -1
end

if (stock<= 0) then
    --3.2.库存不足，返回1
    return 1
end
--3.3.判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1) then
    --3.4.存在，说明重复下单，返回2
    return 2
end
-- 3.5.扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.6.下单(保存)用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)
-- 3.7.原子记录订单预扣，供状态查询和定时对账使用
redis.call('hset',reservationKey,'orderId',orderId)
redis.call('hset',reservationKey,'userId',userId)
redis.call('hset',reservationKey,'voucherId',voucherId)
redis.call('hset',reservationKey,'reservedAt',reservedAt)
redis.call('hset',reservationKey,'updatedAt',reservedAt)
redis.call('hset',reservationKey,'publishStatus','PENDING')
redis.call('hset',reservationKey,'processStatus','PENDING')
redis.call('expire',reservationKey,604800)
redis.call('zadd',pendingKey,reservedAt,orderId)
return 0
