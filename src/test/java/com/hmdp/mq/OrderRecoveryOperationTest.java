package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderFailureRetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import javax.annotation.Resource;

/** 显式指定hash和action后才执行，避免普通测试意外处理真实订单。 */
@SpringBootTest
@EnabledIfSystemProperty(named = "recovery.hash", matches = "[a-fA-F0-9]{64}")
class OrderRecoveryOperationTest {
    @Resource
    private VoucherOrderFailureRetryService service;

    @Test
    void operate() {
        String hash = System.getProperty("recovery.hash");
        String action = System.getProperty("recovery.action", "inspect");
        switch (action) {
            case "inspect": System.out.println(service.inspect(hash)); break;
            case "retry": System.out.println(service.checkAndRetry(hash)); break;
            case "cancel": System.out.println(service.cancel(hash)); break;
            default: throw new IllegalArgumentException("action必须是inspect、retry或cancel");
        }
    }
}
