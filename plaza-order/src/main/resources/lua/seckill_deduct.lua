-- seckill_deduct.lua
-- KEYS[1]: 活动库存 Key (如 seckill:stock:1)
-- KEYS[2]: 已购买用户集合 Key (如 seckill:bought_users:1)
-- ARGV[1]: 扣减数量 (通常为1)
-- ARGV[2]: 用户ID (userId)

local stockKey = KEYS[1]
local boughtUsersKey = KEYS[2]
local deductCount = tonumber(ARGV[1])
local userId = ARGV[2]

-- 1. 检查库存是否存在（预热时应该写入）
local currentStock = redis.call('GET', stockKey)
if not currentStock then
    return -1 -- 返回-1表示活动不存在或未预热
end

currentStock = tonumber(currentStock)

-- 2. 检查用户是否已经购买过
local hasBought = redis.call('SISMEMBER', boughtUsersKey, userId)
if hasBought == 1 then
    return -2 -- 返回-2表示该用户已经参与过抢购，不能重复购买
end

-- 3. 检查库存是否充足
if currentStock < deductCount then
    return -3 -- 返回-3表示库存不足
end

-- 4. 扣减库存，并将用户加入已购买集合
redis.call('DECRBY', stockKey, deductCount)
redis.call('SADD', boughtUsersKey, userId)

return 1 -- 返回1表示扣减成功
