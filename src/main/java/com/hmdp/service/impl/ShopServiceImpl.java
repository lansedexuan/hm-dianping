package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //hop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
/*    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//店铺id唯一

        //2 判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断命中的是否是空值
        if(shopJson !=null){//存在且空白，一定是空值 相当于 shopJson == ""
            //返回错误信息
            return null;
        }

        //4 未命中 查询数据库
        Shop shop = getById(id);

        //5 查询数据库不存在 返回错误
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        //6 查询数据库存在 写入redis 并设置缓存过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7 返回
        return shop;
    }*/

    //线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存穿透
     * @param id
     * @return
     */
/*    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//店铺id唯一

        //2 判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //3不存在 为空 直接返回
            return null;
        }

        //4 命中 需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期 直接返回店铺信息
            return shop;
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
                return shop;
            }
            //6.3 过期 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4 返回过期商铺信息
        return shop;
    }*/

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);//店铺id唯一

        //2 判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断命中的是否是空值
        if(shopJson !=null){//存在且空白，一定是空值 相当于 shopJson == ""
            //返回错误信息
            return null;
        }

        //实现缓存重建
        //4.1 获取互斥锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if(!isLock){
                //4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4 成功，根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);

            //5 查询数据库不存在 返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            //6 查询数据库存在 写入redis 并设置缓存过期时间
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放互斥锁
            unlock(lockKey);
        }

        //8 返回
        return shop;
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

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional//事务
    public Result update(Shop shop) {
        //1 更新数据库
        updateById(shop);

        //2 删除缓存
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY+id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
