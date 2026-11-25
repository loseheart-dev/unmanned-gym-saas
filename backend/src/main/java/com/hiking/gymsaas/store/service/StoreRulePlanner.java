package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.util.Hashes;
import com.hiking.gymsaas.common.util.UtcTimes;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 规则计划的纯函数：完整小时段起点、营业窗口覆盖判断、预览摘要。
 *
 * <p>与 StoreRules 拆分只是为了不让已有文件变大；同样可单测、无副作用。
 */
public final class StoreRulePlanner {
    private StoreRulePlanner() { }

    /** 该业务日按营业规则可生成的完整 60 分钟段起点（业务时区）。 */
    public static List<LocalDateTime> starts(LocalTime open, LocalTime close, boolean is24h,
                                             boolean endsNextDay, LocalDate date) {
        LocalDateTime windowStart;
        LocalDateTime windowEnd;
        if (is24h) {
            windowStart = date.atStartOfDay();
            windowEnd = date.plusDays(1).atStartOfDay();
        } else {
            windowStart = LocalDateTime.of(date, open);
            LocalDate endDate = endsNextDay ? date.plusDays(1) : date;
            windowEnd = LocalDateTime.of(endDate, close);
        }
        List<LocalDateTime> starts = new ArrayList<>();
        LocalDateTime cursor = windowStart;
        while (!cursor.plusMinutes(StoreRules.SLOT_MINUTES).isAfter(windowEnd)) {
            starts.add(cursor);
            cursor = cursor.plusMinutes(StoreRules.SLOT_MINUTES);
        }
        return starts;
    }

    /**
     * 给定 UTC 时段是否被计划中的营业窗口完整覆盖（按**北京时间**归属营业日）。
     *
     * <p>跨午夜（endsNextDay）时，次日 00:00–close 的时段属于前一天的营业窗口：
     * 例如窗口 22:00–04:00，26 日 00:00–01:00 属于 25 日窗口，必须判定为覆盖；
     * 若直接按起始日取 26 日 22:00–27 日 04:00 会错误地判成不覆盖。
     */
    public static boolean covered(LocalTime open, LocalTime close, boolean is24h, boolean endsNextDay,
                                  LocalDateTime startUtc, LocalDateTime endUtc) {
        LocalDateTime start = business(startUtc);
        LocalDateTime end = business(endUtc);
        if (is24h) return Duration.between(start, end).toMinutes() > 0;
        LocalDate businessDay = start.toLocalDate();
        if (endsNextDay && start.toLocalTime().isBefore(close)) {
            businessDay = businessDay.minusDays(1);
        }
        LocalDateTime windowStart = LocalDateTime.of(businessDay, open);
        LocalDateTime windowEnd = endsNextDay
                ? LocalDateTime.of(businessDay.plusDays(1), close)
                : LocalDateTime.of(businessDay, close);
        return !start.isBefore(windowStart) && !end.isAfter(windowEnd);
    }

    /** 预览摘要：方案 + 当前承诺量（已售时段的 id/时间/占用）+ 商家与门店。 */
    public static String previewHash(Store store, LocalTime open, LocalTime close, boolean is24h,
                                     boolean endsNextDay, int capacity, long slotPriceCent,
                                     long overtimePriceCent, long overtimeCapCent, int maxReserved,
                                     List<StoreSlot> committed) {
        String commitments = committed.stream()
                .map(slot -> slot.getId() + ":" + slot.getStartAt() + ":" + slot.getEndAt() + ":"
                        + slot.getReservedCount())
                .collect(Collectors.joining(","));
        String canonical = String.join("|", store.getMerchantId().toString(), store.getId().toString(),
                StoreRules.time(open), StoreRules.time(close), String.valueOf(is24h), String.valueOf(endsNextDay),
                String.valueOf(capacity), String.valueOf(slotPriceCent), String.valueOf(overtimePriceCent),
                String.valueOf(overtimeCapCent), String.valueOf(maxReserved), commitments);
        return Hashes.sha256(canonical);
    }

    public static LocalDateTime business(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(UtcTimes.BUSINESS_ZONE).toLocalDateTime();
    }
}
