package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Component
public class VoucherOrderMessageListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    private final RetryTemplate orderRetry = RetryTemplate.builder()
            .maxAttempts(3) //最多重试3次
            .fixedBackoff(1000) //固定间隔1000毫秒
            .retryOn(RuntimeException.class)
            .build();
    @RabbitListener(
            queues = RabbitMqConstants.VOUCHER_ORDER_QUEUE,
            containerFactory = "manualAckListenerContainerFactory"
    )
    public void listenVoucherOrder(
            VoucherOrder voucherOrder,
            Message message,
            Channel channel) throws IOException {
        // 获取消息的deliveryTag
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long orderId = voucherOrder.getId();
        try {
            orderRetry.execute(context -> {
                log.info("处理优惠券订单，orderId={}, attempt={}",orderId, context.getRetryCount() + 1);
                voucherOrderService.handleVoucherOrder(voucherOrder);
                return null;
            });
        }catch (RuntimeException e) {
                log.error("订单处理失败，拒绝消息且不重新入队，orderId={}", orderId, e);
                channel.basicNack(deliveryTag, false, false);
                return;
        }

        channel.basicAck(deliveryTag, false);
        log.info("优惠券订单消息ACK成功，orderId={}, deliveryTag={}",orderId, deliveryTag);

        }
}