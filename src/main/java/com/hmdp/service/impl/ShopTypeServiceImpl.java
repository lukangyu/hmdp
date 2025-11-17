package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final String CACHE_SHOP_TYPE_KEY_ZSET = "cache:shop-type:zset";
    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */

    @Override
    public Result queryTypeList() {
        //使用zset缓存，缓存数据结构为zset，缓存数据为json字符串，缓存数据为店铺类型列表
        String key = CACHE_SHOP_TYPE_KEY_ZSET;
        Set<String> typeJsonSet = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        // 缓存命中
        if (typeJsonSet != null && !typeJsonSet.isEmpty()) {
            // 存在，直接返回
            log.info("店铺类型缓存命中");
            List<ShopType> typeList = typeJsonSet.stream().map(typeJson -> JSONUtil.toBean(typeJson, ShopType.class)).toList();
            return Result.ok(typeList);
        }
        log.info("店铺类型缓存未命中");
        // 不存在，查询数据库

        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null) {
            // 数据库不存在
            return Result.fail("店铺类型不存在");
        }
        // 数据库存在，写入缓存
        for (ShopType shopType : typeList) {
            log.info("店铺类型写入缓存");
            // 序列化 ShopType 对象
            String json = JSONUtil.toJsonStr(shopType);
            // add(Key, Value, Score)
            stringRedisTemplate.opsForZSet().add(key, json, shopType.getSort());
        }

        return Result.ok(typeList);

    }
}
