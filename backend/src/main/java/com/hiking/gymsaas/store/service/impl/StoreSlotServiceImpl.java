package com.hiking.gymsaas.store.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.security.CurrentIdentity;
import com.hiking.gymsaas.common.util.Ids;
import com.hiking.gymsaas.common.web.ConfigurationEtags;
import com.hiking.gymsaas.operation.service.AuditService;
import com.hiking.gymsaas.store.mapper.StoreMapper;
import com.hiking.gymsaas.store.mapper.StoreSlotMapper;
import com.hiking.gymsaas.store.model.dto.StoreRuleRequests;
import com.hiking.gymsaas.store.model.entity.StoreSlot;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.StoreRules;
import com.hiking.gymsaas.store.service.StoreSlotService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * 时段生成与单时段调整（§4.14、§4.15）。
 *
 * <p>4.14 POST 返回 200 且 data 仅 accepted；以 (storeId,startAt,endAt) 唯一键防重。
 * <p>4.15 幂等 + If-Match（取后台时段列表项 etag）；只允许修改尚未开始且 reservedCount=0 的时段，
 * 已承诺返回 409 SLOT_COMMITTED；摘要只对可写配置计算。
 */
@Service
@Profile("!bootstrap")
public class StoreSlotServiceImpl implements StoreSlotService {
    private final StoreMapper stores;
    private final StoreSlotMapper slots;
    private final SlotGenerationService generation;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public StoreSlotServiceImpl(StoreMapper stores, StoreSlotMapper slots, SlotGenerationService generation,
                                IdempotencyService idempotency, AuditService audit) {
        this.stores = stores;
        this.slots = slots;
        this.generation = generation;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreRuleViews.Accepted> generate(String storeId,
            StoreRuleRequests.GenerateSlots input, String key) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("generateStoreSlots", "POST",
                "/api/v1/admin/stores/" + id + "/slot-generation", key, input,
                StoreRuleViews.Accepted.class, () -> {
                    var store = stores.selectById(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    int inserted = generation.generate(store, LocalDateTime.now(ZoneOffset.UTC));
                    audit.append(merchantId, "STORE_SLOTS_GENERATED", "STORE", id, input.reason(),
                            Map.of("summary", "补齐未来七天时段", "inserted", inserted));
                    return new IdempotencyService.Outcome<>(200, new StoreRuleViews.Accepted(true));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreViews.AdminSlot> adjustSlot(String storeId, String slotId,
            StoreRuleRequests.AdjustSlot input, String key, String ifMatch) {
        long id = Ids.parse(storeId);
        long slotPrimaryKey = Ids.parse(slotId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("adjustStoreSlot", "PATCH",
                "/api/v1/admin/stores/" + id + "/slots/" + slotPrimaryKey, key, input,
                StoreViews.AdminSlot.class, () -> {
                    StoreSlot slot = slots.lock(slotPrimaryKey, id);
                    if (slot == null || !slot.getStoreId().equals(id)) {
                        throw new BusinessException(ErrorCode.NOT_FOUND);
                    }
                    // 逐项 etag：只由可写配置字段（capacity/price/status）生成
                    ConfigurationEtags.requireMatch(ifMatch, StoreRules.slotEtag(slot));
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    if (slot.getStartAt() == null || !slot.getStartAt().isAfter(now)
                            || (slot.getReservedCount() != null && slot.getReservedCount() > 0)) {
                        throw new BusinessException(ErrorCode.SLOT_COMMITTED);
                    }
                    slots.update(null, new LambdaUpdateWrapper<StoreSlot>()
                            .eq(StoreSlot::getId, slotPrimaryKey)
                            .set(StoreSlot::getCapacity, input.capacity())
                            .set(StoreSlot::getPriceCent, input.priceCent())
                            .set(StoreSlot::getStatus, input.status()));
                    audit.append(merchantId, "STORE_SLOT_ADJUSTED", "STORE", id, input.reason(),
                            Map.of("summary", "调整未售时段", "slotId", slotPrimaryKey,
                                    "beforeCapacity", slot.getCapacity(), "afterCapacity", input.capacity(),
                                    "beforePriceCent", slot.getPriceCent(), "afterPriceCent", input.priceCent(),
                                    "beforeStatus", slot.getStatus(), "afterStatus", input.status()));
                    StoreSlot updated = slots.selectById(slotPrimaryKey);
                    var store = stores.selectById(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    // 与 4.13/下单一致：门店状态、暂停准入、时段状态共同决定 bookable
                    var state = StoreRules.slotState(updated, store.getStatus(),
                            Boolean.TRUE.equals(store.getAdmissionPaused()), now);
                    return new IdempotencyService.Outcome<>(200, new StoreViews.AdminSlot(
                            updated.getId().toString(), updated.getStoreId().toString(),
                            StoreRules.utc(updated.getStartAt()), StoreRules.utc(updated.getEndAt()),
                            updated.getCapacity() == null ? 0 : updated.getCapacity(),
                            StoreRules.remaining(updated), updated.getPriceCent() == null ? 0 : updated.getPriceCent(),
                            updated.getStatus(), state.bookable(), state.unavailableReason(),
                            StoreRules.slotEtag(updated)));
                });
    }
}
