package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.service.impl.ShopServiceImpl.CACHE_REBUILD_EXECUTOR;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;


@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数
     * @param stringRedisTemplate
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 设置缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogical(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存封装
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     */
    public <R, ID>  R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix+id;
        //1 从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);//店铺id唯一

        //2 判断是否存在
        if(StrUtil.isNotBlank(Json)){
            //3 存在 直接返回
            return JSONUtil.toBean(Json,type);
        }

        //判断命中的是否是空值
        if(Json !=null){//存在且空白，一定是空值 相当于 shopJson == ""
            //返回错误信息
            return null;
        }

        //4 未命中 查询数据库
        R r = dbFallback.apply(id);

        //5 查询数据库不存在 返回错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //6 查询数据库存在 写入redis 并设置缓存过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7 返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix+id;
        //1 从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);//店铺id唯一

        //2 判断是否存在
        if(StrUtil.isBlank(Json)){
            //3不存在 为空 直接返回
            return null;
        }

        //4 命中 需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期 直接返回店铺信息
            return r;
        }

        //5.2已过期 需要缓存重建


        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取成功
        if(isLock){
            //再次判断缓存是否过期
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期 直接返回店铺信息
                return r;
            }
            //6.3 过期 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);//函数式编程
                    //写入redis
                    this.setWithLogical(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }  finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4 返回过期商铺信息
        return r;
    }

    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
