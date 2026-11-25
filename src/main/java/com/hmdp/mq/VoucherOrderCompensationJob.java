package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderFailureRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherOrderCompensationJob {
    private final JdbcTemplate jdbc;
    private final VoucherOrderFailureRetryService recovery;

    @Scheduled(fixedDelay = 5000)
    public void resumePendingCompensations() {
        for (Long orderId : jdbc.queryForList("SELECT order_id FROM tb_voucher_order_recovery " +
                "WHERE recovery_state='CANCEL_PENDING' ORDER BY update_time LIMIT 50", Long.class)) {
            try {
                recovery.compensate(orderId);
            } catch (RuntimeException e) {
                log.error("取消已生效，Redis补偿等待重试或人工核对，orderId={}", orderId, e);
            }
        }
    }
}
