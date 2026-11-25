package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.OrderRecoveryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.mq.RabbitMqConstants.VOUCHER_ORDER_EXCHANGE;
import static com.hmdp.mq.RabbitMqConstants.VOUCHER_ORDER_OUTBOX_KEY;
import static com.hmdp.mq.RabbitMqConstants.VOUCHER_ORDER_ROUTING_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherOrderOutboxRelay {

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final OrderRecoveryStore recoveryStore;

    @Scheduled(fixedDelay = 1000)
    public void relay() {
        ZSetOperations<String, String> outbox = redisTemplate.opsForZSet();
        Set<String> dueOrders = outbox.rangeByScore(
                VOUCHER_ORDER_OUTBOX_KEY, 0, System.currentTimeMillis(), 0, 10
        );
        if (dueOrders == null) {
            return;
        }

        for (String entry : dueOrders) {
            Long orderId = Long.valueOf(entry.split(":")[0]);
            if (recoveryStore.cancelled(orderId)) {
                outbox.remove(VOUCHER_ORDER_OUTBOX_KEY, entry);
                continue;
            }
            // 保留原记录，先把下次发送时间推后 10 秒
            outbox.add(VOUCHER_ORDER_OUTBOX_KEY, entry,
                    System.currentTimeMillis() + 10_000);

            try {
                publish(entry);
                outbox.remove(VOUCHER_ORDER_OUTBOX_KEY, entry);
                log.info("订单消息已确认，移除待发记录 entry={}", entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("订单消息发送未确认，将重新尝试 entry={}", entry, e);
            }
        }
    }

    private void publish(String entry)
            throws InterruptedException, ExecutionException, TimeoutException {

        String[] fields = entry.split(":");
        VoucherOrder order = new VoucherOrder()
                .setId(Long.parseLong(fields[0]))
                .setUserId(Long.parseLong(fields[1]))
                .setVoucherId(Long.parseLong(fields[2]));

        // 补发沿用同一个订单 ID，但每次投递的关联 ID 都不同
        CorrelationData correlation = new CorrelationData(
                order.getId() + ":" + UUID.randomUUID()
        );

        rabbitTemplate.convertAndSend(
                VOUCHER_ORDER_EXCHANGE,
                VOUCHER_ORDER_ROUTING_KEY,
                order,
                message -> {
                    message.getMessageProperties()
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlation
        );

        CorrelationData.Confirm confirm =
                correlation.getFuture().get(3, TimeUnit.SECONDS);

        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ NACK: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ Return: " + correlation.getReturned().getReplyText()
            );
        }
    }
}
