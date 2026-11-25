package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.Objects;

@Repository
@DependsOn("orderRecoverySchemaInitializer")
@RequiredArgsConstructor
public class OrderRecoveryStore {
    private final JdbcTemplate jdbc;

    // 调用者必须处于事务中，创建订单、重投和取消都锁同一行。
    public String lock(VoucherOrder order) {
        jdbc.update("INSERT INTO tb_voucher_order_recovery (order_id,user_id,voucher_id) " +
                        "VALUES (?,?,?) ON DUPLICATE KEY UPDATE order_id=order_id",
                order.getId(), order.getUserId(), order.getVoucherId());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id,voucher_id,recovery_state FROM tb_voucher_order_recovery " +
                        "WHERE order_id=? FOR UPDATE", order.getId());
        if (!Objects.equals(((Number) row.get("user_id")).longValue(), order.getUserId())
                || !Objects.equals(((Number) row.get("voucher_id")).longValue(), order.getVoucherId())) {
            throw new IllegalStateException("订单号对应的用户或优惠券不一致");
        }
        return row.get("recovery_state").toString();
    }

    public void state(Long orderId, String state) {
        jdbc.update("UPDATE tb_voucher_order_recovery SET recovery_state=?, " +
                "update_time=CURRENT_TIMESTAMP WHERE order_id=?", state, orderId);
    }

    public boolean cancelledForUser(Long orderId, Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_recovery " +
                        "WHERE order_id=? AND user_id=? AND recovery_state IN ('CANCEL_PENDING','CANCELLED')",
                Long.class, orderId, userId) > 0;
    }

    public boolean cancelled(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_recovery " +
                        "WHERE order_id=? AND recovery_state IN ('CANCEL_PENDING','CANCELLED')",
                Long.class, orderId) > 0;
    }
}
