package com.hmdp.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理失败的订单
 */
@Service
@RequiredArgsConstructor
@DependsOn("orderRecoverySchemaInitializer")
public class VoucherOrderFailureService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void archive(Message message) {
        byte[] body = message.getBody();
        String hash = DigestUtil.sha256Hex(body);
        String headers =
                message.getMessageProperties().getHeaders().toString();

        Long orderId = null;
        try {
            VoucherOrder order = objectMapper.readValue(body, VoucherOrder.class);
            orderId = order == null ? null : order.getId();
        } catch (java.io.IOException ignored) {
            // 非法消息也要归档，保留原文供人工检查。
        }
        jdbcTemplate.update(
                "INSERT INTO tb_voucher_order_failure " +
                        "(message_hash, order_id, raw_body, message_headers) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE message_headers=VALUES(message_headers), " +
                        "order_id=VALUES(order_id), handle_status=CASE " +
                        "WHEN handle_status='RETRY_PENDING' THEN 'PENDING' ELSE handle_status END",
                hash,
                orderId,
                body,
                headers
        );
    }
}
