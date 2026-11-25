package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderFailureService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherOrderDeadLetterListener {

    private final VoucherOrderFailureService failureService;

    @RabbitListener(
            queues = "hmdp.voucher.order.dlq",
            containerFactory = "manualAckListenerContainerFactory"
    )
    public void listen(Message message, Channel channel)
            throws IOException {

        long deliveryTag =
                message.getMessageProperties().getDeliveryTag();

        try {
            failureService.archive(message);
        } catch (RuntimeException e) {
            log.error("保存死信处理记录失败，消息重新入队", e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        // archive通过Spring代理返回时，数据库事务已经提交
        channel.basicAck(deliveryTag, false);

        log.info("死信已保存到失败记录表并ACK，deliveryTag={}", deliveryTag);
    }
}