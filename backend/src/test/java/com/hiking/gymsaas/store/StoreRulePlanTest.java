package com.hiking.gymsaas.store;

import static org.junit.jupiter.api.Assertions.*;

import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import com.hiking.gymsaas.store.service.StoreRulePlanner;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 规则计划纯函数测试：完整小时段起点、窗口覆盖、预览摘要稳定性。 */
class StoreRulePlanTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    @Test
    void fullHourStartsRespectWindowAndTail() {
        // 24h：每天 24 段
        assertEquals(24, StoreRulePlanner.starts(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true, false, DAY).size());
        // 08:00-22:30：14 个完整小时段，剩余 30 分钟尾段不生成
        var starts = StoreRulePlanner.starts(LocalTime.of(8, 0), LocalTime.of(22, 30), false, false, DAY);
        assertEquals(14, starts.size());
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 0)), starts.get(0));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(21, 0)), starts.get(starts.size() - 1));
        // 跨午夜 22:00-04:00：6 段且跨到次日
        var cross = StoreRulePlanner.starts(LocalTime.of(22, 0), LocalTime.of(4, 0), false, true, DAY);
        assertEquals(6, cross.size());
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(22, 0)), cross.get(0));
        assertEquals(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(3, 0)), cross.get(cross.size() - 1));
    }

    @Test
    void coverageUsesBusinessZoneWindow() {
        // 2026-11-25 08:00 Asia/Shanghai == 2026-11-25 00:00 UTC
        LocalDateTime start = LocalDateTime.of(2026, 11, 25, 0, 0);
        assertTrue(StoreRulePlanner.covered(LocalTime.of(8, 0), LocalTime.of(23, 0), false, false,
                start, start.plusHours(1)));
        assertFalse(StoreRulePlanner.covered(LocalTime.of(9, 0), LocalTime.of(23, 0), false, false,
                start, start.plusHours(1)));
        // 跨午夜：22:00-04:00 的 23:00-24:00 时段覆盖
        LocalDateTime late = LocalDateTime.of(2026, 11, 25, 14, 0); // 22:00 Asia/Shanghai
        assertTrue(StoreRulePlanner.covered(LocalTime.of(22, 0), LocalTime.of(4, 0), false, true,
                late.plusHours(1), late.plusHours(2)));
        // 跨午夜窗口外（次日 05:00 UTC+8 = 21:00 UTC）不覆盖
        assertFalse(StoreRulePlanner.covered(LocalTime.of(22, 0), LocalTime.of(4, 0), false, true,
                late.plusHours(8), late.plusHours(9)));
    }

    @Test
    void previewHashCoversPlanAndCommitmentsAndIsStable() {
        Store store = store();
        StoreSlot committed = new StoreSlot();
        committed.setId(7L);
        committed.setStartAt(LocalDateTime.of(2026, 11, 25, 2, 0));
        committed.setEndAt(LocalDateTime.of(2026, 11, 25, 3, 0));
        committed.setReservedCount(3);

        String hash = StoreRulePlanner.previewHash(store, LocalTime.of(8, 0), LocalTime.of(22, 0), false,
                false, 10, 1000, 100, 3000, 3, List.of(committed));
        assertEquals(hash, StoreRulePlanner.previewHash(store, LocalTime.of(8, 0), LocalTime.of(22, 0), false,
                false, 10, 1000, 100, 3000, 3, List.of(committed)));
        assertNotEquals(hash, StoreRulePlanner.previewHash(store, LocalTime.of(8, 0), LocalTime.of(22, 0), false,
                false, 8, 1000, 100, 3000, 3, List.of(committed)));
        assertNotEquals(hash, StoreRulePlanner.previewHash(store, LocalTime.of(8, 0), LocalTime.of(22, 0), false,
                false, 10, 1000, 100, 3000, 3, List.of()));
        assertFalse(hash.contains("\""), "previewHash 应为不带引号的摘要");
    }

    private Store store() {
        Store store = new Store();
        store.setId(1L);
        store.setMerchantId(1L);
        store.setStatus("OPEN");
        store.setOpenTime(LocalTime.of(8, 0));
        store.setCloseTime(LocalTime.of(22, 0));
        store.setIs24h(false);
        store.setEndsNextDay(false);
        store.setCapacity(10);
        store.setSlotPriceCent(1000L);
        store.setOvertimePriceCent(100L);
        store.setOvertimeCapCent(3000L);
        return store;
    }
    @Test
    void crossMidnightCoverageUsesBeijingBusinessDay() {
        // 窗口 22:00–04:00（北京时间）；UTC = 北京时间 - 8h
        LocalTime open = LocalTime.of(22, 0);
        LocalTime close = LocalTime.of(4, 0);
        // 25 日 22:00–23:00 北京 == 25 日 14:00–15:00 UTC
        assertTrue(StoreRulePlanner.covered(open, close, false, true,
                LocalDateTime.of(2026, 11, 25, 14, 0), LocalDateTime.of(2026, 11, 25, 15, 0)));
        // 26 日 00:00–01:00 北京 == 25 日 16:00–17:00 UTC，归属 25 日业务日
        assertTrue(StoreRulePlanner.covered(open, close, false, true,
                LocalDateTime.of(2026, 11, 25, 16, 0), LocalDateTime.of(2026, 11, 25, 17, 0)));
        // 26 日 03:00–04:00 北京 == 25 日 19:00–20:00 UTC，仍在 25 日窗口内
        assertTrue(StoreRulePlanner.covered(open, close, false, true,
                LocalDateTime.of(2026, 11, 25, 19, 0), LocalDateTime.of(2026, 11, 25, 20, 0)));
        // 26 日 04:00–05:00 北京 == 25 日 20:00–21:00 UTC，已超出窗口
        assertFalse(StoreRulePlanner.covered(open, close, false, true,
                LocalDateTime.of(2026, 11, 25, 20, 0), LocalDateTime.of(2026, 11, 25, 21, 0)));
        // 25 日 21:00–22:00 北京 == 25 日 13:00–14:00 UTC，窗口开始前
        assertFalse(StoreRulePlanner.covered(open, close, false, true,
                LocalDateTime.of(2026, 11, 25, 13, 0), LocalDateTime.of(2026, 11, 25, 14, 0)));
    }
}