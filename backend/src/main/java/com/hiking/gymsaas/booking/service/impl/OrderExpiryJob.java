package com.hiking.gymsaas.booking.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时触发未支付订单过期回收；间隔可通过 env `booking.expiry.fixed-delay-ms` 覆盖（测试用）。 */
@Component
@Profile("!bootstrap")
public class OrderExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);
    private final OrderExpiryService expiry;

    public OrderExpiryJob(OrderExpiryService expiry) { this.expiry = expiry; }

    @Scheduled(fixedDelayString = "${booking.expiry.fixed-delay-ms:60000}",
            initialDelayString = "${booking.expiry.initial-delay-ms:15000}")
    public void run() {
        try {
            int cancelled = expiry.expireBatch();
            if (cancelled > 0) LOG.info("Expired unpaid booking orders: {}", cancelled);
        } catch (RuntimeException ex) {
            // 单次扫描失败不终止后续调度；不打印订单/用户敏感信息
            LOG.warn("Expired order scan failed: {}", ex.getClass().getName());
        }
    }
}
