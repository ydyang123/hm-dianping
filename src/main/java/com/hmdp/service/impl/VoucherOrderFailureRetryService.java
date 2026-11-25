package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.RabbitMqConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class VoucherOrderFailureRetryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final OrderRecoveryStore store;
    private final TransactionTemplate transaction;
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>();

    static {
        CANCEL_SCRIPT.setLocation(new ClassPathResource("cancel-voucher-order.lua"));
        CANCEL_SCRIPT.setResultType(Long.class);
    }

    public VoucherOrderFailureRetryService(JdbcTemplate jdbc, ObjectMapper mapper,
            StringRedisTemplate redis, OrderRecoveryStore store,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.redis = redis;
        this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public String inspect(String hash) {
        VoucherOrder order = readFailure(hash);
        return transaction.execute(status -> {
            String state = store.lock(order);
            if (originalCreated(order)) {
                store.state(order.getId(), "CREATED");
                failureState(order.getId(), "RESOLVED");
                return "RESOLVED";
            }
            return state;
        });
    }

    public String checkAndRetry(String hash) {
        VoucherOrder order = readFailure(hash);
        return transaction.execute(status -> {
            String state = store.lock(order);
            if (originalCreated(order)) {
                failureState(order.getId(), "RESOLVED");
                return "RESOLVED";
            }
            if (!"ACTIVE".equals(state)) {
                throw new IllegalStateException("当前状态禁止重投: " + state);
            }
            assertNoOtherOrder(order);
            assertReservation(order);
            Long available = jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher " +
                    "WHERE voucher_id=? AND stock>0", Long.class, order.getVoucherId());
            if (available == null || available == 0) {
                throw new IllegalStateException("优惠券不存在或MySQL库存不足，请先核对故障原因");
            }
            redis.opsForZSet().add(RabbitMqConstants.VOUCHER_ORDER_OUTBOX_KEY, entry(order), 0);
            failureState(order.getId(), "RETRY_PENDING");
            return "RETRY_PENDING";
        });
    }

    public String cancel(String hash) {
        VoucherOrder order = readFailure(hash);
        // 第一阶段提交取消围栏。Redis不可用时旧消息也已无法再创建订单。
        transaction.executeWithoutResult(status -> {
            String state = store.lock(order);
            if (originalCreated(order) || "CREATED".equals(state)) {
                throw new IllegalStateException("订单已经创建，不能按失败订单释放库存");
            }
            if ("CANCELLED".equals(state) || "CANCEL_PENDING".equals(state)) {
                return;
            }
            assertNoOtherOrder(order);
            assertReservation(order);
            store.state(order.getId(), "CANCEL_PENDING");
            failureState(order.getId(), "CANCEL_PENDING");
        });
        compensate(order.getId());
        return "CANCELLED";
    }

    public void compensate(Long orderId) {
        transaction.executeWithoutResult(status -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT user_id,voucher_id FROM " +
                    "tb_voucher_order_recovery WHERE order_id=?", orderId);
            VoucherOrder order = new VoucherOrder().setId(orderId)
                    .setUserId(((Number) row.get("user_id")).longValue())
                    .setVoucherId(((Number) row.get("voucher_id")).longValue());
            if (!"CANCEL_PENDING".equals(store.lock(order))) {
                return;
            }
            Long result = redis.execute(CANCEL_SCRIPT, Arrays.asList(
                    "seckill:stock:" + order.getVoucherId(),
                    "seckill:order:" + order.getVoucherId(),
                    "seckill:reservation:" + order.getVoucherId(),
                    "seckill:compensated:" + orderId,
                    RabbitMqConstants.VOUCHER_ORDER_OUTBOX_KEY),
                    String.valueOf(order.getUserId()), String.valueOf(orderId), entry(order));
            if (result == null || result < 0) {
                throw new IllegalStateException("补偿待核对，Lua结果=" + result + ", orderId=" + orderId);
            }
            store.state(orderId, "CANCELLED");
            failureState(orderId, "CANCELLED");
        });
    }

    private VoucherOrder readFailure(String hash) {
        List<byte[]> rows = jdbc.query("SELECT raw_body FROM tb_voucher_order_failure " +
                "WHERE message_hash=?", (rs, i) -> rs.getBytes(1), hash);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("失败记录不存在");
        }
        try {
            VoucherOrder order = mapper.readValue(rows.get(0), VoucherOrder.class);
            if (order == null || order.getId() == null || order.getUserId() == null
                    || order.getVoucherId() == null || order.getId() <= 0
                    || order.getUserId() <= 0 || order.getVoucherId() <= 0) {
                throw new IllegalArgumentException("死信缺少有效的订单、用户或优惠券编号");
            }
            // 为重试/补偿关联订单，不改动原始消息正文。
            jdbc.update("UPDATE tb_voucher_order_failure SET order_id=? WHERE message_hash=?",
                    order.getId(), hash);
            return order;
        } catch (IOException e) {
            throw new IllegalArgumentException("死信正文格式错误，需要人工核对", e);
        }
    }

    private boolean originalCreated(VoucherOrder order) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id,voucher_id FROM tb_voucher_order WHERE id=?", order.getId());
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        if (!Objects.equals(((Number) row.get("user_id")).longValue(), order.getUserId())
                || !Objects.equals(((Number) row.get("voucher_id")).longValue(), order.getVoucherId())) {
            throw new IllegalStateException("原订单内容不匹配，需要人工核对");
        }
        return true;
    }

    private void assertNoOtherOrder(VoucherOrder order) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=? AND voucher_id=?",
                Long.class, order.getUserId(), order.getVoucherId()) > 0) {
            throw new IllegalStateException("用户存在另一笔同券订单，需要人工核对");
        }
    }

    private void assertReservation(VoucherOrder order) {
        Object owner = redis.opsForHash().get("seckill:reservation:" + order.getVoucherId(),
                String.valueOf(order.getUserId()));
        if (!String.valueOf(order.getId()).equals(owner)) {
            throw new IllegalStateException("缺少匹配的预占订单号，历史死信需人工核对，不能自动退库存");
        }
    }

    private void failureState(Long orderId, String state) {
        jdbc.update("UPDATE tb_voucher_order_failure SET handle_status=?, " +
                "update_time=CURRENT_TIMESTAMP WHERE order_id=?", state, orderId);
    }

    private String entry(VoucherOrder order) {
        return order.getId() + ":" + order.getUserId() + ":" + order.getVoucherId();
    }
}
