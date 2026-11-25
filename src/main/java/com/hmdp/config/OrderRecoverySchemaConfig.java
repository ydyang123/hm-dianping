package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
public class OrderRecoverySchemaConfig {
    @Bean
    public Object orderRecoverySchemaInitializer(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("db/order-recovery.sql"))
                .execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long columns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order_failure' " +
                "AND column_name='order_id'", Long.class);
        if (columns != null && columns == 0) {
            jdbc.execute("ALTER TABLE tb_voucher_order_failure ADD COLUMN order_id BIGINT NULL, " +
                    "ADD INDEX idx_failure_order(order_id)");
        }
        // 存量库补齐 (user_id, voucher_id) 联合唯一索引，与 hmdp.sql 中的 uk_user_voucher 保持一致
        Long orderTable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order'", Long.class);
        Long uniqueIndex = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' " +
                "AND index_name='uk_user_voucher'", Long.class);
        if (orderTable != null && orderTable > 0 && uniqueIndex != null && uniqueIndex == 0) {
            Long duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM tb_voucher_order " +
                    "GROUP BY user_id, voucher_id HAVING COUNT(*) > 1) t", Long.class);
            if (duplicates != null && duplicates == 0) {
                jdbc.execute("ALTER TABLE tb_voucher_order " +
                        "ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id)");
            }
        }
        return new Object();
    }
}
