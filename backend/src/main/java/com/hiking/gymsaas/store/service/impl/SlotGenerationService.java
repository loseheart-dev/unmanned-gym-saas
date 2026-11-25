package com.hiking.gymsaas.store.service.impl;

import com.hiking.gymsaas.store.mapper.StoreSlotMapper;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.service.StoreRulePlanner;
import com.hiking.gymsaas.store.service.StoreRules;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 时段生成（§4.14，并与 §4.10 规则应用共用）。
 *
 * <p>容量模板：v1.1 schema 只有门店默认 capacity（没有 §20.9 的 capacity_schedule 字段），
 * 因此按北京时间每小时应用门店当前容量/价格；INSERT IGNORE 保证已存在的时段（含 4.15 人工调整）不被覆盖，
 * 人工覆盖优先于生成模板。</p>
 * <p>只补"含当天共 7 个自然日"内的**完整 60 分钟**时段；业务时间按 Asia/Shanghai、存储 UTC；
 * 跨午夜按起始日归档不截断；唯一键 (store_id,start_at,end_at) 防重；只生成开始时间仍在未来的时段。
 */
@Service
@Profile("!bootstrap")
public class SlotGenerationService {
    private final StoreSlotMapper slots;

    public SlotGenerationService(StoreSlotMapper slots) { this.slots = slots; }

    public int generate(Store store, LocalDateTime nowUtc) {
        int capacity = store.getCapacity() == null ? 0 : store.getCapacity();
        int price = store.getSlotPriceCent() == null ? 0 : store.getSlotPriceCent().intValue();
        boolean is24h = Boolean.TRUE.equals(store.getIs24h());
        boolean endsNextDay = Boolean.TRUE.equals(store.getEndsNextDay());
        int inserted = 0;
        LocalDate today = StoreRules.today();
        for (int offset = 0; offset < StoreRules.BOOKING_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            for (LocalDateTime businessStart : StoreRulePlanner.starts(store.getOpenTime(), store.getCloseTime(),
                    is24h, endsNextDay, date)) {
                LocalDateTime startUtc = businessStart.atZone(com.hiking.gymsaas.common.util.UtcTimes.BUSINESS_ZONE)
                        .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                LocalDateTime endUtc = businessStart.plusMinutes(StoreRules.SLOT_MINUTES)
                        .atZone(com.hiking.gymsaas.common.util.UtcTimes.BUSINESS_ZONE)
                        .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                if (!startUtc.isAfter(nowUtc)) continue;
                inserted += slots.insertIgnore(store.getMerchantId(), store.getId(), startUtc, endUtc,
                        capacity, price);
            }
        }
        return inserted;
    }
}
