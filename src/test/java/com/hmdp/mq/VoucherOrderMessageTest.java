package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class VoucherOrderMessageTest {
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    void sendVoucherOrder() throws InterruptedException {
        VoucherOrder voucherOrder = new VoucherOrder()
                .setId(10000000L)
                .setUserId(100L)
                .setVoucherId(12L);
        rabbitTemplate.convertAndSend(
                RabbitMqConstants.VOUCHER_ORDER_EXCHANGE,
                RabbitMqConstants.VOUCHER_ORDER_ROUTING_KEY,
                voucherOrder);
        //仅用于学习测试，给异步消费者一些处理时间
        Thread.sleep(1000);
    }

}
