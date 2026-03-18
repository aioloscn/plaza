if redis.call('exists', KEYS[1]) == 1 then
    local stock = tonumber(redis.call('get', KEYS[1]))
    local num = tonumber(ARGV[1])
    if (stock >= num) then
        return redis.call('decrby', KEYS[1], num)
    else
        return -1
    end
else
    return -2
end