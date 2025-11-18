package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /**
     * 缓存重建线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = queryWithPassThrow(id);
        //使用互斥锁解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithPassThrow(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            log.info("缓存命中");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //缓存未命中，判断是否为空字符串

        Shop shop = null;
        try {
            String lockKey = LOCK_SHOP_KEY + id;
            //获取互斥锁
            if(!tryLock(id)) {
                log.info("获取锁失败");
                Thread.sleep(50);
                return queryWithPassThrow(id);
            }
            if (Objects.nonNull(shopJson)) {
                //缓存中存在之前缓存的空字符串，直接返回失败
                log.info("缓存命中，但为空字符串");
                return null;
            }
            // 不存在，根据id查询数据库
            shop = this.getById(id);
            //模拟延迟
            Thread.sleep(200);
            if (shop == null) {
                // 数据库中不存在
                log.info("店铺不存在,缓存空对象");
                stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            log.info("店铺信息写入缓存");
        }
        finally {
            //释放锁
            unLock(id);
        }

        return shop;
    }

    /**
     * 使用互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            log.info("缓存命中");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //缓存未命中，判断是否为空字符串
        if (Objects.nonNull(shopJson)) {
            //缓存中存在之前缓存的空字符串，直接返回失败
            log.info("缓存命中，但为空字符串");
            return null;
        }
        // 不存在，根据id查询数据库
        Shop shop = this.getById(id);
        if (shop == null) {
            // 数据库中不存在
            log.info("店铺不存在,缓存空对象");
            stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        log.info("店铺信息写入缓存");
        return shop;
    }

    /**
     * 使用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // 未命中，直接返回空
            log.info("缓存未命中");
            return null;
        }
        //缓存命中，判断是否过期
        //先将redisData反序列化为对像，然后获取其中的数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //要将redisData中的data转为json对象，这是由于data的格式是object，无法直接转化为shop，所以要先将data转为json对象，再转为shop
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            log.info("缓存未过期");

            return shop;
        }
        log.info("缓存已过期");
        // 缓存已过期，先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(id)) {
            // 获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, CACHE_SHOP_LOGICAL_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(id);
                }
            });
        }

        //获取锁失败，再次查询缓存，判断缓存是否重建，这里的双检是有必要的，因为缓存重建是异步的，可能会有并发问题
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // 未命中，直接返回空
            log.info("缓存未命中");
            return null;
        }

        // 3.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        shop = JSONUtil.toBean(data, Shop.class);
        expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return shop;
        }

        // 4、返回过期数据
        return shop;
    }

    /**
     * 缓存重建
     * @param id
     */
    private void saveShop2Redis(Long id, long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = this.getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Transactional // 添加事务, 保证数据一致性
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        boolean update = this.updateById(shop);
        if (!update) {
            log.info("更新数据库失败");
            throw new RuntimeException("更新数据库失败");
        }
        log.info("更新数据库成功");
        // 2.删除缓存
        boolean delete = stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        if (!delete) {
            log.info("删除缓存失败");
            throw new RuntimeException("删除缓存失败");
        }
        log.info("删除缓存成功");
        return Result.ok();

    }

    private boolean tryLock(Long id) {
        // 尝试获取锁, 返回true表示获取锁成功,使用setIfAbsent，利用redis中setnx的特性，设置10s的过期时间，防止意外发生，导致锁未被释放产生死锁
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(CACHE_SHOP_KEY + id, "1", 10, TimeUnit.SECONDS));
    }

    private void unLock(Long id) {
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }

}
