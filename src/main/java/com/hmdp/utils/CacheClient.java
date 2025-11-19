package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Component
@Slf4j
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建缓存客户端
     * @param stringRedisTemplate
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 缓存数据
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
        log.info("缓存数据成功");
    }

    /**
     * 逻辑过期缓存数据
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        log.info("缓存逻辑过期数据成功");
    }

    /**
     * 缓存空对象解决缓存穿透
     * @param  keyPrefix 前缀
     * @param id 名称
     * @param type  类型
     * @param dbFallback 回调函数
     * @param time  时间
     * @param unit  时间单位
     * @return
     */
    public <R, ID> R queryWithPassThrow(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            log.info("缓存命中");
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //缓存未命中，判断是否为空字符串
        if (Objects.nonNull(json)) {
            //缓存中存在之前缓存的空字符串，直接返回失败
            log.info("缓存命中，但为空字符串");
            return null;
        }
        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            // 数据库中不存在
            log.info("店铺不存在,缓存空对象");
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        this.set(key, r, time, unit);
        log.info("店铺信息写入缓存");
        return r;
    }

    /**
     * 使用互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID>R queryWithMutex(String keyPrefix,String lockKeyPrefix , ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 存在，直接返回
            log.info("缓存命中");
            return JSONUtil.toBean(json, type);
        }
        //缓存未命中，判断是否为空字符串

        R r = null;
        String lockKey = lockKeyPrefix + id;
        try {

            //获取互斥锁
            if(!tryLock(lockKey)) {
                log.info("获取锁失败");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }
            if (Objects.nonNull(json)) {
                //缓存中存在之前缓存的空字符串，直接返回失败
                log.info("缓存命中，但为空字符串");
                return null;
            }
            // 不存在，根据id查询数据库
            r = dbFallback.apply(id);
            //模拟延迟
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (r == null) {
                // 数据库中不存在
                log.info("店铺不存在,缓存空对象");
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入redis
            this.set(key, r, time, unit);
            log.info("店铺信息写入缓存");
        }
        finally {
            //释放锁
            unLock(lockKey);
        }

        return r;
    }


    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 使用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID>  R queryWithLogicalExpire(String keyPrefix,String lockKeyPrefix , ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 未命中，直接返回空
            log.info("缓存未命中");
            return null;
        }
        //缓存命中，判断是否过期
        //先将redisData反序列化为对像，然后获取其中的数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //要将redisData中的data转为json对象，这是由于data的格式是object，无法直接转化为shop，所以要先将data转为json对象，再转为shop
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            log.info("缓存未过期");
            return r;
        }
        log.info("缓存已过期");
        // 缓存已过期，先获取互斥锁
        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            // 获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //获取锁失败，再次查询缓存，判断缓存是否重建，这里的双检是有必要的，因为缓存重建是异步的，可能会有并发问题
        json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 未命中，直接返回空
            log.info("缓存未命中");
            return null;
        }

        // 3.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(json, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        r = JSONUtil.toBean(data, type);
        expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return r;
        }

        // 4、返回过期数据
        return r;
    }

    private boolean tryLock(String key) {
        // 尝试获取锁, 返回true表示获取锁成功,使用setIfAbsent，利用redis中setnx的特性，设置10s的过期时间，防止意外发生，导致锁未被释放产生死锁
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }

    private void unLock(String  key) {
        stringRedisTemplate.delete(key);
    }
}
