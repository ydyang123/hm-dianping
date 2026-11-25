CREATE TABLE IF NOT EXISTS tb_voucher_order_recovery (
    order_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    recovery_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_recovery_state (recovery_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_voucher_order_failure (
    message_hash CHAR(64) NOT NULL PRIMARY KEY,
    order_id BIGINT NULL,
    raw_body LONGBLOB NOT NULL,
    message_headers TEXT NOT NULL,
    handle_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_failure_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
