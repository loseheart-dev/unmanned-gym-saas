package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.util.UtcTimes;
import com.hiking.gymsaas.common.web.ConfigurationEtags;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeSet;

/**
 * 门店规则纯函数（可单测）：营业时间窗口、时段边界、配置 ETag、坐标与时间展示、公开可售口径。
 */
public final class StoreRules {
    public static final int SLOT_MINUTES = 60;
    public static final int BOOKING_DAYS = 7;
    public static final String TIMEZONE = "Asia/Shanghai";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private StoreRules() { }

    public static String time(LocalTime value) {
        return value == null ? null : value.format(TIME);
    }

    /** 后台坐标：十进制定点字符串（≤7 位小数）。 */
    public static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    public static void validateOpening(LocalTime open, LocalTime close, boolean is24h, boolean endsNextDay) {
        if (is24h) {
            if (endsNextDay) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            return;
        }
        if (open == null || close == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (endsNextDay) {
            if (!close.isBefore(open)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        } else if (!close.isAfter(open)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public static BigDecimal coordinate(String raw, String axis) {
        if (raw == null || raw.isBlank()) return null;
        try {
            var value = new BigDecimal(raw.trim());
            int min = "latitude".equals(axis) ? -90 : -180;
            int max = "latitude".equals(axis) ? 90 : 180;
            if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0
                    || value.scale() > 7) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** 门店配置 ETag：只由可写配置字段生成，排序保证稳定。 */
    public static String etag(Store store) {
        List<String> images = store.getImages() == null ? List.of() : store.getImages();
        return ConfigurationEtags.of(String.join("|",
                nullSafe(store.getName()), nullSafe(store.getAddress()), nullSafe(decimal(store.getLatitude())),
                nullSafe(decimal(store.getLongitude())), nullSafe(store.getDescription()), String.join(",", images),
                nullSafe(time(store.getOpenTime())), nullSafe(time(store.getCloseTime())),
                String.valueOf(store.getIs24h()), String.valueOf(store.getEndsNextDay()),
                String.valueOf(store.getCapacity()), String.valueOf(store.getSlotPriceCent()),
                String.valueOf(store.getOvertimePriceCent()), String.valueOf(store.getOvertimeCapCent()),
                nullSafe(store.getStatus()), String.valueOf(store.getAdmissionPaused())));
    }

    /** 单个时段的配置 ETag；不含 reservedCount 的日常变化。 */
    public static String slotEtag(StoreSlot slot) {
        return ConfigurationEtags.of(nullSafe(String.valueOf(slot.getCapacity()))
                + "|" + nullSafe(String.valueOf(slot.getPriceCent())) + "|" + nullSafe(slot.getStatus()));
    }

    public static long remaining(StoreSlot slot) {
        long capacity = slot.getCapacity() == null ? 0 : slot.getCapacity();
        long reserved = slot.getReservedCount() == null ? 0 : slot.getReservedCount();
        return Math.max(0, capacity - reserved);
    }

    public static boolean bookable(StoreSlot slot, LocalDateTime nowUtc) {
        return "OPEN".equals(slot.getStatus()) && slot.getStartAt() != null
                && slot.getStartAt().isAfter(nowUtc) && remaining(slot) > 0;
    }

    public static String unavailableReason(StoreSlot slot, LocalDateTime nowUtc) {
        if (!"OPEN".equals(slot.getStatus())) return "SLOT_CLOSED";
        if (slot.getStartAt() == null || !slot.getStartAt().isAfter(nowUtc)) return "SLOT_STARTED";
        if (remaining(slot) <= 0) return "SLOT_FULL";
        return null;
    }

    public static LocalDateTime businessDayStartUtc(LocalDate date) {
        return date.atStartOfDay(UtcTimes.BUSINESS_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    public static LocalDateTime businessDayEndUtc(LocalDate date) {
        return date.plusDays(1).atStartOfDay(UtcTimes.BUSINESS_ZONE).withZoneSameInstant(java.time.ZoneOffset.UTC)
                .toLocalDateTime();
    }

    public static LocalDate today() {
        return LocalDate.now(UtcTimes.BUSINESS_ZONE);
    }

    public static LocalDate lastBookableDate() {
        return today().plusDays(BOOKING_DAYS - 1);
    }

    /** 每日营业窗口总分钟数（用于预览的不足一小时尾段）。 */
    public static int windowMinutes(LocalTime open, LocalTime close, boolean is24h, boolean endsNextDay) {
        if (is24h) return 24 * 60;
        int minutes = endsNextDay
                ? (int) Duration.between(open, close).toMinutes() + 24 * 60
                : (int) Duration.between(open, close).toMinutes();
        if (minutes < 0) minutes += 24 * 60;
        return Math.max(0, minutes);
    }

    public static String sortedPermissions(List<String> values) {
        return String.join(",", new TreeSet<>(values == null ? List.of() : values));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
    /** 瞬时字段统一按契约输出 UTC（序列化为 Z 结尾）。 */
    public static java.time.OffsetDateTime utc(LocalDateTime time) {
        return time == null ? null : time.atOffset(java.time.ZoneOffset.UTC);
    }

    /**
     * 时段可售口径必须与下单规则一致：门店非 OPEN、暂停准入、时段关闭、已开始、已满都不可预约，
     * 并在 unavailableReason 中返回原因，避免前端显示可预约但下单失败。
     */
    public record SlotState(boolean bookable, String unavailableReason) { }

    public static SlotState slotState(StoreSlot slot, String storeStatus, boolean admissionPaused,
                                      LocalDateTime nowUtc) {
        if (!"OPEN".equals(storeStatus)) return new SlotState(false, "STORE_CLOSED");
        if (admissionPaused) return new SlotState(false, "ADMISSION_PAUSED");
        if (!"OPEN".equals(slot.getStatus())) return new SlotState(false, "SLOT_CLOSED");
        if (slot.getStartAt() == null || !slot.getStartAt().isAfter(nowUtc)) {
            return new SlotState(false, "SLOT_STARTED");
        }
        if (remaining(slot) <= 0) return new SlotState(false, "SLOT_FULL");
        return new SlotState(true, null);
    }
}