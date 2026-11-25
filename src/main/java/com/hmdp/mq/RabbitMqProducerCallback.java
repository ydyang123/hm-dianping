package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Component
public class RabbitMqProducerCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init(){
        //消息是否成功达到交换机
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String messageId = getMessageId(correlationData);
            if(ack){
                log.info("RabbitMQ交换机确认成功，messageId={}",messageId);
                return;
            }
            log.error("RabbitMQ交换机确认失败，messageId={}, cause={}",messageId,cause);
        });

        // 消息到达交换机，但无法路由到任何队列。
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error(
                    "RabbitMQ消息路由失败，exchange={}，routingKey={}，" +
                            "replyCode={}，replyText={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText()
            );
        });
    }

    private String getMessageId(CorrelationData correlationData) {
        return correlationData == null ? "unknown" : correlationData.getId();
    }
}
