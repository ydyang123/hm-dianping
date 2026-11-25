package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-"; //区分不同JVM

    public SimpleRedisLock() {
    }

    public SimpleRedisLock( String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    @Override
    public boolean tryLock(long time) {
        //1.设置Key
        String key = KEY_PREFIX + name;
        // 获取线程标示,区分不同线程
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, time, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation( new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
        /*
        //1.设置key
        String key = KEY_PREFIX + name;
        //2.获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //3.获取Redis中的标识
        String id = stringRedisTemplate.opsForValue().get(key);
        if(threadId.equals(id)){
            //标识相同,释放锁
            //4.删除
            stringRedisTemplate.delete(key);
        }
        */
    }

}
