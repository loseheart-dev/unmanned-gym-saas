package com.hiking.gymsaas.booking;

import static org.junit.jupiter.api.Assertions.*;

import com.hiking.gymsaas.booking.service.OrderRules;
import com.hiking.gymsaas.store.model.entity.Store;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 下单/支付规则纯函数：支付截止取较早者、订单号/流水号格式、规则快照字段。 */
class OrderRulesTest {

    @Test
    void payDeadlineTakesTheEarlierOfFifteenMinutesAndSlotStart() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 8, 0);
        assertEquals(now.plusMinutes(15), OrderRules.payDeadline(now, now.plusHours(2)));
        assertEquals(now.plusMinutes(5), OrderRules.payDeadline(now, now.plusMinutes(5)));
        // 截止必须严格不晚于时段开始，这是数据库 ck_booking_order_1 与 Q033 的共同要求
        assertFalse(OrderRules.payDeadline(now, now.plusMinutes(5)).isAfter(now.plusMinutes(5)));
    }

    @Test
    void orderAndPaymentNumbersAreServerGenerated() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 8, 0, 3);
        String orderNo = OrderRules.orderNo(now);
        assertTrue(orderNo.matches("^O20261125080003[A-Z0-9]{6}$"), orderNo);
        String paymentNo = OrderRules.paymentNo(now);
        assertTrue(paymentNo.matches("^P20261125080003[A-Z0-9]{8}$"), paymentNo);
        assertNotEquals(orderNo, OrderRules.orderNo(now));
    }

    @Test
    void ruleSnapshotCarriesBillingBaseline() {
        Store store = new Store();
        store.setName("演示门店");
        store.setSlotPriceCent(1000L);
        store.setOvertimePriceCent(100L);
        store.setOvertimeCapCent(3000L);
        Map<String, Object> snapshot = OrderRules.ruleSnapshot(store, "演示商家");
        assertEquals("v1.0", snapshot.get("version"));
        assertEquals(60, snapshot.get("slotMinutes"));
        assertEquals(0, snapshot.get("entryGraceMinutes"));
        assertEquals(0, snapshot.get("exitGraceMinutes"));
        assertEquals(100L, snapshot.get("overtimePriceCent"));
        assertEquals(3000L, snapshot.get("overtimeCapCent"));
        assertEquals("CEIL_MINUTE", snapshot.get("rounding"));
        assertEquals("Asia/Shanghai", snapshot.get("timezone"));
        assertEquals("演示商家", snapshot.get("merchantName"));
        assertEquals("演示门店", snapshot.get("storeName"));
        assertEquals(1000L, OrderRules.baseAmount(store));
    }
    @Test
    void paymentBoundaryIsStrictlyBeforeDeadline() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 8, 0);
        assertTrue(OrderRules.paymentAllowed(now, now.plusMinutes(1)));
        assertFalse(OrderRules.expired(now, now.plusMinutes(1)));
        // Q034：恰好截止时刻视为过期，不允许确认
        assertFalse(OrderRules.paymentAllowed(now, now));
        assertTrue(OrderRules.expired(now, now));
        assertFalse(OrderRules.paymentAllowed(now, now.minusNanos(1)));
        assertTrue(OrderRules.expired(now, now.minusNanos(1)));
        // 没有截止时间（异常数据）也按过期处理
        assertFalse(OrderRules.paymentAllowed(now, null));
        assertTrue(OrderRules.expired(now, null));
    }
}