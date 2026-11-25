package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    // redis模板
    private final StringRedisTemplate stringRedisTemplate;
    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 缓存重建锁的最大重试次数（每次间隔50ms）
    private static final int MAX_MUTEX_RETRY = 20;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 设置缓存写入
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 设置逻辑过期写入
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // 设置空值解决缓存穿透
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值,
//        if (json != null) {
        if("".equals(json)){
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，写入空值，返回错误。
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }
    // 通过逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在（含空值标记），直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (lockValue != null) {
            // 6.3.成功，开启独立线程，实现缓存重建
            // lockValue 必须由主线程捕获后传入子线程，否则子线程重新计算的线程标识与加锁时不一致，Lua 校验会失败导致锁无法释放
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    if (newR == null) {
                        // 数据已被删除，写入空值标记，避免每次请求都反复重建
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return;
                    }
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    // 线程池任务中的异常无法抛给调用方，记录日志即可
                    log.error("缓存重建失败，key={}", key, e);
                } finally {
                    // 释放锁
                    unlock(lockKey, lockValue);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    // 通过互斥锁解决缓存击穿
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.循环重试：抢到锁则重建缓存，抢不到则休眠后重试（用循环代替递归，避免栈溢出且重试次数可控）
        String lockKey = LOCK_SHOP_KEY + id;
        for (int i = 0; i < MAX_MUTEX_RETRY; i++) {
            // 1.1.从redis查询缓存，可能已被其他线程重建
            String json = stringRedisTemplate.opsForValue().get(key);
            // 1.2.判断是否存在，存在直接返回
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            // 1.3.判断命中的是否是空值，不是null,则就是缓存的空字符串，所以返回null
            if (json != null) {
                return null;
            }
            // 2.实现缓存重建
            // 2.1.获取锁，获取锁的操作必须在 try 块之外，否则未持锁的线程也会在 finally 中释放别人的锁
            String lockValue = tryLock(lockKey);
            if (lockValue == null) {
                // 2.2.获取锁失败，休眠并重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            // 2.3.获取锁成功，重建缓存；只有持锁线程才会走到这里释放自己的锁
            try {
                // 双重检查：等待锁期间可能已有其他线程重建完成
                json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }
                // 根据id查询数据库
                R r = dbFallback.apply(id);
                // 3.不存在，写入空值后返回错误
                if (r == null) {
                    // 将空值写入redis
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 4.存在，写入redis
                this.set(key, r, time, unit);
                return r;
            } finally {
                // 5.释放锁
                unlock(lockKey, lockValue);
            }
        }
        // 6.重试次数耗尽（持锁线程异常或过慢），降级为直接查询数据库
        log.warn("缓存重建获取锁重试次数耗尽，降级查询数据库，key={}", key);
        return dbFallback.apply(id);
    }

    // 锁标识前缀，用于区分不同JVM
    private static final String LOCK_VALUE_PREFIX = UUID.randomUUID().toString(true) + "-";

    // 释放锁的Lua脚本：锁标识一致才删除，保证判断与删除的原子性
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 尝试获取锁，成功返回锁标识（用于释放时校验归属），失败返回null
    private String tryLock(String key) {
        String lockValue = LOCK_VALUE_PREFIX + Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, lockValue, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? lockValue : null;
    }

    // 释放锁，只有锁标识与自身一致时才删除，避免误删他人持有的锁
    private void unlock(String key, String lockValue) {
        if (lockValue == null) {
            return;
        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), lockValue);
    }
}
