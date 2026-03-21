local stockKey = KEYS[1]
local boughtKey = KEYS[2]
local count = tonumber(ARGV[1])
local userId = ARGV[2]
local removed = redis.call('SREM', boughtKey, userId)
if removed == 1 then
    redis.call('INCRBY', stockKey, count)
    return 1
end
return 0
