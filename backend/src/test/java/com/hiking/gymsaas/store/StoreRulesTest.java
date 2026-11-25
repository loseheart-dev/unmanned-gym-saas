package com.hiking.gymsaas.store;

import static org.junit.jupiter.api.Assertions.*;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import com.hiking.gymsaas.store.service.StoreRules;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** 门店规则纯函数测试：营业时间、ETag、时段可售口径、窗口尾段。 */
class StoreRulesTest {

    @Test
    void openingRulesFollowContract() {
        // 24h 门店 endsNextDay 必须 false
        assertDoesNotThrow(() -> StoreRules.validateOpening(LocalTime.of(8, 0), LocalTime.of(23, 0), true, false));
        assertThrows(BusinessException.class,
                () -> StoreRules.validateOpening(LocalTime.of(8, 0), LocalTime.of(23, 0), true, true));
        // 跨日：close < open
        assertDoesNotThrow(() -> StoreRules.validateOpening(LocalTime.of(20, 0), LocalTime.of(2, 0), false, true));
        assertThrows(BusinessException.class,
                () -> StoreRules.validateOpening(LocalTime.of(20, 0), LocalTime.of(21, 0), false, true));
        // 非跨日：close > open
        assertThrows(BusinessException.class,
                () -> StoreRules.validateOpening(LocalTime.of(20, 0), LocalTime.of(2, 0), false, false));
    }

    @Test
    void storeEtagDependsOnWritableConfiguration() {
        Store store = store("演示门店", 10, 1000, "OPEN");
        String etag = StoreRules.etag(store);
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""));
        assertEquals(etag, StoreRules.etag(store));
        store.setName("改名门店");
        String renamed = StoreRules.etag(store);
        assertNotEquals(etag, renamed);
        // 只改非配置字段（灯控/消息观察状态）不改变 ETag：ETag 只由可写配置生成
        store.setLightGeneration(99L);
        store.setMessageHealth("UNKNOWN");
        assertEquals(renamed, StoreRules.etag(store));
    }

    @Test
    void slotAvailabilityUsesRemainingNotRealOccupancy() {
        StoreSlot slot = new StoreSlot();
        slot.setCapacity(10);
        slot.setReservedCount(10);
        slot.setStatus("OPEN");
        LocalDateTime now = LocalDateTime.of(2026, 11, 24, 8, 0);
        slot.setStartAt(now.plusHours(1));
        assertEquals(0, StoreRules.remaining(slot));
        assertFalse(StoreRules.bookable(slot, now));
        assertEquals("SLOT_FULL", StoreRules.unavailableReason(slot, now));

        slot.setReservedCount(4);
        assertEquals(6, StoreRules.remaining(slot));
        assertTrue(StoreRules.bookable(slot, now));
        assertNull(StoreRules.unavailableReason(slot, now));

        slot.setStatus("CLOSED");
        assertEquals("SLOT_CLOSED", StoreRules.unavailableReason(slot, now));

        slot.setStatus("OPEN");
        slot.setStartAt(now.minusMinutes(1));
        assertEquals("SLOT_STARTED", StoreRules.unavailableReason(slot, now));
    }

    @Test
    void windowMinutesExposePartialTail() {
        assertEquals(870, StoreRules.windowMinutes(LocalTime.of(8, 0), LocalTime.of(22, 30), false, false));
        assertEquals(30, StoreRules.windowMinutes(LocalTime.of(8, 0), LocalTime.of(22, 30), false, false) % 60);
        assertEquals(1440, StoreRules.windowMinutes(LocalTime.of(0, 0), LocalTime.of(0, 0), true, false));
        assertEquals(360, StoreRules.windowMinutes(LocalTime.of(22, 0), LocalTime.of(4, 0), false, true));
    }

    @Test
    void coordinatesArePlainDecimalWithSevenScaleLimit() {
        assertEquals("31.2304", StoreRules.decimal(new java.math.BigDecimal("31.2304000")));
        assertEquals("31.2304", StoreRules.coordinate("31.2304", "latitude").stripTrailingZeros().toPlainString());
        assertThrows(BusinessException.class, () -> StoreRules.coordinate("91", "latitude"));
        assertThrows(BusinessException.class, () -> StoreRules.coordinate("31.12345678", "latitude"));
        assertThrows(BusinessException.class, () -> StoreRules.coordinate("abc", "longitude"));
    }

    private Store store(String name, int capacity, long price, String status) {
        Store store = new Store();
        store.setName(name);
        store.setAddress("学校教学楼");
        store.setOpenTime(LocalTime.of(8, 0));
        store.setCloseTime(LocalTime.of(23, 0));
        store.setIs24h(false);
        store.setEndsNextDay(false);
        store.setCapacity(capacity);
        store.setSlotPriceCent(price);
        store.setOvertimePriceCent(100L);
        store.setOvertimeCapCent(3000L);
        store.setStatus(status);
        store.setAdmissionPaused(false);
        store.setImages(java.util.List.of());
        return store;
    }
    @Test
    void slotStateMatchesBookingRules() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 0, 0);
        StoreSlot slot = new StoreSlot();
        slot.setCapacity(10);
        slot.setReservedCount(0);
        slot.setStatus("OPEN");
        slot.setStartAt(now.plusHours(1));

        var open = StoreRules.slotState(slot, "OPEN", false, now);
        assertTrue(open.bookable());
        assertNull(open.unavailableReason());

        // 门店关闭 / 暂停准入：即使时段本身可售也不可预约
        assertEquals("STORE_CLOSED", StoreRules.slotState(slot, "CLOSED", false, now).unavailableReason());
        assertEquals("ADMISSION_PAUSED", StoreRules.slotState(slot, "OPEN", true, now).unavailableReason());

        slot.setStatus("CLOSED");
        assertEquals("SLOT_CLOSED", StoreRules.slotState(slot, "OPEN", false, now).unavailableReason());
        slot.setStatus("OPEN");
        slot.setStartAt(now.minusMinutes(1));
        assertEquals("SLOT_STARTED", StoreRules.slotState(slot, "OPEN", false, now).unavailableReason());
        slot.setStartAt(now.plusHours(1));
        slot.setReservedCount(10);
        assertEquals("SLOT_FULL", StoreRules.slotState(slot, "OPEN", false, now).unavailableReason());
    }

    @Test
    void instantViewsUseUtcZ() {
        var instant = StoreRules.utc(LocalDateTime.of(2026, 11, 25, 2, 0));
        assertEquals("Z", instant.getOffset().getId());
        assertEquals("2026-11-25T02:00Z", instant.toString());
        assertNull(StoreRules.utc(null));
    }
}