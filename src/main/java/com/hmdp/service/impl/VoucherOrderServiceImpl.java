package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.RabbitMqConstants;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 *  优惠券订单服务实现类
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderRecoveryStore recoveryStore;

    @Resource
    private RabbitTemplate rabbitTemplate;

    // 注入自身代理对象：消费者线程不依赖请求线程，避免 proxy 未初始化时的 NPE
    // @Lazy 必须配合 @Autowired，@Resource 不支持 @Lazy
    @Lazy
    @Autowired
    private IVoucherOrderService self;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 创建订单-加锁并调用受事务管理的方法
     * @param voucherOrder
     */
    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            throw new IllegalStateException("获取订单锁失败，userId="+ userId);
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            self.createVoucherOrder(voucherOrder);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底命中：同用户同券已存在订单，事务已回滚（含库存扣减），按幂等成功处理
            log.info("唯一索引拦截重复下单，按幂等跳过，orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), userId, voucherOrder.getVoucherId());
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    /**
     * 创建订单-负责真正写数据库
     * @param voucherOrder
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        String recoveryState = recoveryStore.lock(voucherOrder);
        if ("CANCEL_PENDING".equals(recoveryState) || "CANCELLED".equals(recoveryState)) {
            log.info("已取消订单的旧消息被拦截，orderId={}", voucherOrder.getId());
            return;
        }
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.info("订单已存在，跳过扣库存和写入，消息orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), userId, voucherId);
            return;
        }

        // 3.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            log.error("扣减 MySQL 库存失败");
            throw new IllegalStateException("扣减库存失败, voucherId=" + voucherId);
        }
        // 4.保存订单
//        save(voucherOrder);
        if (!save(voucherOrder)) {
            throw new IllegalStateException("保存订单失败，orderId=" + voucherOrder.getId());
        }
        recoveryStore.state(voucherOrder.getId(), "CREATED");
        log.info(
                "优惠券订单创建成功，orderId={}, userId={}, voucherId={}",
                voucherOrder.getId(),
                userId,
                voucherId
        );

    }

    /**
     * 秒杀优惠券-生产者-接收秒杀请求
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户ID
        Long userId = UserHolder.getUser().getId();
        // 2.生成订单ID
        long orderId = redisIdWorker.nextId("order");
        // 3.执行lua脚本
        // 执行lua脚本，判断用户是否具有购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.singletonList(RabbitMqConstants.VOUCHER_ORDER_OUTBOX_KEY),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 脚本返回null说明执行结果异常，直接失败
        if (result == null) {
            return Result.fail("下单失败，请稍后再试");
        }
        int r = result.intValue();
        // 断结果是否为0
        if (r != 0) {
            // 4.1.不为0 ，代表没有购买资格
            if (r == 1) {
                return Result.fail("库存不足");
            }
            if (r == 2) {
                return Result.fail("不能重复下单");
            }
            // 4.2.为3，代表Redis中没有该优惠券的库存key（未预热或活动不存在）
            return Result.fail("秒杀活动不存在或已结束");
        }
//        VoucherOrder voucherOrder = new VoucherOrder()
//                .setId(orderId)
//                .setUserId(userId)
//                .setVoucherId(voucherId);
//        // 5.发送消息到队列
//        CorrelationData correlationData = new CorrelationData(String.valueOf(orderId));
//        rabbitTemplate.convertAndSend(
//                RabbitMqConstants.VOUCHER_ORDER_EXCHANGE,
//                RabbitMqConstants.VOUCHER_ORDER_ROUTING_KEY,
//                voucherOrder,
//                correlationData);
        // 6.返回订单id
        return Result.ok(String.valueOf(orderId));
    }

    /*
     * 查询订单状态
     */
    @Override
    public Result queryOrderStatus(Long orderId) {
        Long userId = UserHolder.getUser().getId();

        boolean created = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .count() > 0;

        if (created) {
            return Result.ok("CREATED");
        }
        return Result.ok(recoveryStore.cancelledForUser(orderId, userId)
                ? "CANCELLED" : "NOT_READY");
    }

}
