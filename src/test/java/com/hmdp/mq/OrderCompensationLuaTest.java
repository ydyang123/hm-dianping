package com.hmdp.mq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/** 仅使用随机测试优惠券Key，测试结束精确清理，不启动应用或消息消费者。 */
@EnabledIfSystemProperty(named = "recovery.redis", matches = "true")
class OrderCompensationLuaTest {
    @Test
    void refundIsIdempotentAndDoesNotReleaseNewReservation() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                properties.getProperty("spring.redis.host"),
                Integer.parseInt(properties.getProperty("spring.redis.port", "6379")));
        config.setPassword(RedisPassword.of(properties.getProperty("spring.redis.password")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String voucher = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L));
        List<String> keys = Arrays.asList("seckill:stock:" + voucher, "seckill:order:" + voucher,
                "seckill:reservation:" + voucher, "test:compensated:" + voucher, "test:outbox:" + voucher);
        DefaultRedisScript<Long> reserve = new DefaultRedisScript<>();
        reserve.setLocation(new ClassPathResource("seckill.lua"));
        reserve.setResultType(Long.class);
        DefaultRedisScript<Long> cancel = new DefaultRedisScript<>();
        cancel.setLocation(new ClassPathResource("cancel-voucher-order.lua"));
        cancel.setResultType(Long.class);
        try {
            redis.opsForValue().set(keys.get(0), "10");
            assertEquals(0L, redis.execute(reserve, Arrays.asList(keys.get(4)), voucher, "100", "123"));
            assertEquals("9", redis.opsForValue().get(keys.get(0)));
            assertEquals(-1L, redis.execute(cancel, keys, "100", "999", "999:100:" + voucher));
            assertEquals("9", redis.opsForValue().get(keys.get(0)));
            assertEquals(0L, redis.execute(cancel, keys, "100", "123", "123:100:" + voucher));
            assertEquals("10", redis.opsForValue().get(keys.get(0)));
            assertFalse(redis.opsForSet().isMember(keys.get(1), "100"));
            assertNull(redis.opsForHash().get(keys.get(2), "100"));
            assertEquals(0L, redis.execute(reserve, Arrays.asList(keys.get(4)), voucher, "100", "124"));
            assertEquals(1L, redis.execute(cancel, keys, "100", "123", "123:100:" + voucher));
            assertEquals("9", redis.opsForValue().get(keys.get(0)));
            assertEquals("124", redis.opsForHash().get(keys.get(2), "100"));
            assertTrue(redis.opsForSet().isMember(keys.get(1), "100"));
        } finally {
            redis.delete(keys);
            factory.destroy();
        }
    }
}
