package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于Redis的全局唯一ID生成器
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP =  1704067200L;  // 2024-01-01 00:00:00 UTC
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 生成id，落库后永久有效
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //把"1970 年至今的绝对秒数"换成"从某个自定义起点开始的相对秒数"
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        //生成key，只负责发号，当天用完废除
        String key = "icr:" + keyPrefix + ":" + date;
        long count = stringRedisTemplate.opsForValue().increment(key);
        // 规避当天key被废除后产生重复id
        stringRedisTemplate.expire(key, Duration.ofDays(2));
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}