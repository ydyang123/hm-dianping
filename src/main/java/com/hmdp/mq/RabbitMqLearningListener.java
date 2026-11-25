package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RabbitMqLearningListener {
    //Spring 启动后创建一个消费者，持续监听 hmdp.test.queue。
    @RabbitListener(queues = "hmdp.test.queue")
    public void listenTestQueue(String message) {
        log.info("收到 RabbitMQ 测试消息：{}", message);
    }
}