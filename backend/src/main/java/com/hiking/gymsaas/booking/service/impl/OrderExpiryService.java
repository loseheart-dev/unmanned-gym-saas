package com.hiking.gymsaas.booking.service.impl;

import com.hiking.gymsaas.booking.mapper.BookingMapper;
import com.hiking.gymsaas.booking.mapper.BookingOrderMapper;
import com.hiking.gymsaas.booking.service.OrderRules;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 未支付订单超时回收（Q033/Q035）。
 *
 * <p>与支付确认共用订单行锁：确认用条件 UPDATE（pay_deadline &gt; now），
 * 过期用条件 UPDATE（status=PENDING_PAYMENT 且 capacity_held=1），两者互斥、至多一条转换成功。
 * 取消后释放时段占位（reserved_count-1），避免名额长期占用；已支付订单不受影响。
 */
@Service
@Profile("!bootstrap")
public class OrderExpiryService {
    /** 单次批量上限，避免长事务。 */
    private static final int BATCH_SIZE = 100;
    private static final String REASON = "PAYMENT_TIMEOUT";

    private final BookingMapper reader;
    private final BookingOrderMapper orders;
    private final TransactionTemplate transactions;

    public OrderExpiryService(BookingMapper reader, BookingOrderMapper orders,
                              PlatformTransactionManager manager) {
        this.reader = reader;
        this.orders = orders;
        this.transactions = new TransactionTemplate(manager);
    }

    /** 扫描并取消已过支付截止的待支付订单，返回本次回收数量。 */
    public int expireBatch() {
        LocalDateTime scanNow = reader.lockedNow();
        List<Long> candidates = orders.expiredCandidates(scanNow, BATCH_SIZE);
        int cancelled = 0;
        for (Long orderId : candidates) {
            Integer result = transactions.execute(status -> expireOne(orderId));
            if (result != null) cancelled += result;
        }
        return cancelled;
    }

    private int expireOne(long orderId) {
        var order = orders.lockOrderById(orderId);
        if (order == null || !"PENDING_PAYMENT".equals(order.getStatus())
                || !Boolean.TRUE.equals(order.getCapacityHeld())) {
            return 0;
        }
        LocalDateTime now = reader.lockedNow();
        if (OrderRules.paymentAllowed(now, order.getPayDeadline())) {
            return 0;
        }
        if (orders.cancelUnpaid(orderId, now, REASON) != 1) {
            return 0;
        }
        reader.releaseCapacity(order.getSlotId(), order.getMerchantId(), order.getStoreId());
        return 1;
    }
}
