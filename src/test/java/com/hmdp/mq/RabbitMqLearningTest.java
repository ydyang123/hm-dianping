package com.hmdp.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class RabbitMqLearningTest {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    void sendTestMessage() throws InterruptedException {
        CorrelationData correlationData =
                new CorrelationData("confirm-success-1");
        rabbitTemplate.convertAndSend(
                "hmdp.test.direct",
                "test",
                "Publisher Confirm正常测试",
                correlationData
        );
        // Confirm是异步回调，测试方法不能立即结束
        Thread.sleep(2000);
    }

    //测试交换机不存在，导致消息无法路由，从而触发Return Callback
    @Test
    void testPublisherConfirmFailed()
            throws InterruptedException {

        CorrelationData correlationData =
                new CorrelationData("confirm-failed-1");

        rabbitTemplate.convertAndSend(
                "hmdp.exchange.not-exist",
                "test",
                "不存在的交换机测试",
                correlationData
        );

        Thread.sleep(2000);
    }

    //测试Routing Key不存在，导致消息无法路由，从而触发Return Callback
    @Test
    void testPublisherReturn()
            throws InterruptedException {

        CorrelationData correlationData =
                new CorrelationData("return-failed-1");

        rabbitTemplate.convertAndSend(
                "hmdp.test.direct",
                "wrong.routing.key",
                "错误Routing Key测试",
                correlationData
        );

        Thread.sleep(2000);
    }
}