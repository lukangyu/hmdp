 -- 参数列表
 -- 优惠卷id
 local voucherId = ARGV[1];

 -- 用户id
 local userId = ARGV[2];


 -- 数据key

 -- 库存key
 local stockKey = "seckill:stock:" .. voucherId;

 -- 订单key
 local orderKey = "seckill:order:" .. userId;

 -- 脚本业务
 -- 判断库存是否充足
 local stock = redis.call("get", stockKey);
 -- 注意这里的tonumber()，因为redis.call()返回的是字符串，需要转换成数字
 if (tonumber(stock) <= 0) then
    -- 秒杀活动不存在
    return 1;
 end

 -- 判断用户是否已经下过单，使用sismember()，
 if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已经下过单
    return 2;
 end

 -- 减库存，下订单
 redis.call("incrby", stockKey, -1);
 redis.call("sadd", orderKey, userId);
 -- 发送消息，这里使用stream

 return 0;