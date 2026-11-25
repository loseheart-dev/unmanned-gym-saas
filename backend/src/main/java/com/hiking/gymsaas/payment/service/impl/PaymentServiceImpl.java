package com.hiking.gymsaas.payment.service.impl;

import com.hiking.gymsaas.booking.service.OrderRules;
import com.hiking.gymsaas.payment.mapper.PaymentMapper;
import com.hiking.gymsaas.payment.model.entity.PaymentRecord;
import com.hiking.gymsaas.payment.service.PaymentService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 模拟支付流水（Q036）：渠道固定 MOCK，金额由订单决定；
 * businessKey = ORDER_FEE:{orderId}，(merchant_id, business_key) 唯一键保证换 HTTP 请求键也不会重复收费。
 */
@Service
@Profile("!bootstrap")
public class PaymentServiceImpl implements PaymentService {
    private final PaymentMapper mapper;

    public PaymentServiceImpl(PaymentMapper mapper) { this.mapper = mapper; }

    @Override
    public PaymentRecord recordOrderFee(long merchantId, long userId, long orderId, long amountCent) {
        if (amountCent <= 0) return null;
        String businessKey = "ORDER_FEE:" + orderId;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            mapper.insertReceipt(merchantId, userId, OrderRules.paymentNo(now), orderId, businessKey, amountCent, now);
        } catch (DuplicateKeyException ex) {
            // 并发/重放：同一收费项已成功，读取原流水
            return mapper.byBusinessKey(merchantId, businessKey);
        }
        return mapper.byBusinessKey(merchantId, businessKey);
    }

    @Override
    public PaymentRecord orderReceipt(long orderId) { return mapper.orderReceipt(orderId); }
}
