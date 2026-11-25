package com.hiking.gymsaas.store.model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.time.OffsetDateTime;
import java.util.List;

/** 门店视图：公开（匿名，坐标 number、不含容量/etag）与后台（坐标 string、含 etag）两套。 */
public final class StoreViews {
    private StoreViews() { }

    public record PublicStoreItem(String id, String merchantId, String merchantName, String name,
                                  String address, String status, long slotPriceCent,
                                  Long estimatedOccupancy, String occupancySource, String deviceSource) { }

    public record PublicStoreDetail(PublicStoreItem store, String description, List<String> images,
                                    BigDecimal latitude, BigDecimal longitude, String openTime, String closeTime,
                                    boolean is24h, boolean endsNextDay, String timezone, int slotMinutes,
                                    int bookingDays, long overtimePriceCent, long overtimeCapCent,
                                    boolean admissionPaused, String pauseReason,
                                    int entryGraceMinutes, int exitGraceMinutes) { }

    public record PublicSlot(String id, String storeId, OffsetDateTime startAt, OffsetDateTime endAt,
                             long capacity, long remaining, long priceCent, String status,
                             boolean bookable, String unavailableReason) { }

    public record PublicSlotList(String storeId, LocalDate date, LocalDate lastBookableDate,
                                 List<PublicSlot> items) { }

    public record AdminStore(String id, String name, String address, String latitude, String longitude,
                             String description, List<String> images, String openTime, String closeTime,
                             boolean is24h, boolean endsNextDay, int capacity, long slotPriceCent,
                             long overtimePriceCent, long overtimeCapCent, String status,
                             boolean admissionPaused, String pauseReason, String etag) { }

    public record AdminSlot(String id, String storeId, OffsetDateTime startAt, OffsetDateTime endAt,
                            long capacity, long remaining, long priceCent, String status,
                            boolean bookable, String unavailableReason, String etag) { }

    public record AdminSlotList(String storeId, LocalDate date, LocalDate lastBookableDate,
                                List<AdminSlot> items) { }

    /** 员工/门店配置 ETag 的服务层返回值。 */
    public record AdminStoreEntry(AdminStore store, String etag) { }
}
