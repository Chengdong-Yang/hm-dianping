package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setwithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json,type);
        }
        //这里是检验""
        if ("".equals(json)) {
            return null;
        }
        //这里检验的是null
        //4.不存在，根据id查数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6。存在，写入redis
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) throws InterruptedException {
        String key =keyPrefix+ id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.存在，直接返回
            return null;
        }
        //4.命中，需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime=redisData.getExpireTime();

        //5。判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 没有过期，直接返回店铺信息
            return r;
        }
        //5.2 已经过期，需要缓存重建

        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey=LOCK_SHOP_KEY+ id;
        boolean isLock=tryLock(lockKey);
        //6.2 判断是否获取锁成功
        //6.3 成功，开启独立线程，开启缓存重建
        if(isLock){
            //6.3通过线程池创建
            //成功，开启独立线程，完成缓存重建的任务
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1= dbFallback.apply(id);
                    this.setwithLogicExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //6.4 返回商铺信息
        // 6.4不存在，根据id查询数据库
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
