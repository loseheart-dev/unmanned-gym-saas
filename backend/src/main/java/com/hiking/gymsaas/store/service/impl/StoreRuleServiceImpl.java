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
import com.hiking.gymsaas.store.mapper.StoreOpsMapper;
import com.hiking.gymsaas.store.mapper.StoreSlotMapper;
import com.hiking.gymsaas.store.model.dto.StoreRuleRequests;
import com.hiking.gymsaas.store.model.entity.Store;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.StoreRulePlanner;
import com.hiking.gymsaas.store.service.StoreRuleService;
import com.hiking.gymsaas.store.service.StoreRules;
import com.hiking.gymsaas.store.service.StoreSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * 门店规则与营业状态（§4.9–§4.12）。
 *
 * <ul>
 *   <li>4.9 预览只演算不落库；previewHash = 方案 + 当前承诺量摘要，不是授权凭据；</li>
 *   <li>4.10 应用规则：锁内重算预览并比对 previewHash（过期 409 PREVIEW_STALE），
 *       有冲突 409 RULE_CONFLICT（带订单 ID），容量不得低于已有承诺；只更新未售未来时段；</li>
 *   <li>4.11 转 DISABLED 前检查未结束到店/未履约订单/未决设备命令；</li>
 *   <li>4.12 恢复准入前当前读检查门店状态与在场人数/容量，不安全 409 OCCUPANCY_UNKNOWN。</li>
 * </ul>
 */
@Service
@Profile("!bootstrap")
public class StoreRuleServiceImpl implements StoreRuleService {
    private final StoreMapper stores;
    private final StoreSlotMapper slots;
    private final StoreOpsMapper ops;
    private final SlotGenerationService generation;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public StoreRuleServiceImpl(StoreMapper stores, StoreSlotMapper slots, StoreOpsMapper ops,
                                SlotGenerationService generation, IdempotencyService idempotency,
                                AuditService audit) {
        this.stores = stores;
        this.slots = slots;
        this.ops = ops;
        this.generation = generation;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public StoreRuleViews.RulePreview preview(String storeId, StoreRuleRequests.RulePreview input) {
        long id = Ids.parse(storeId);
        var store = stores.selectById(id);
        if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        StoreRules.validateOpening(input.openTime(), input.closeTime(),
                Boolean.TRUE.equals(input.is24h()), Boolean.TRUE.equals(input.endsNextDay()));
        return computePreview(store, input.openTime(), input.closeTime(), Boolean.TRUE.equals(input.is24h()),
                Boolean.TRUE.equals(input.endsNextDay()), input.capacity(), input.slotPriceCent(),
                input.overtimePriceCent(), input.overtimeCapCent());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreViews.AdminStoreEntry> applyRules(String storeId,
            StoreRuleRequests.ApplyRules input, String key, String ifMatch) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        StoreRules.validateOpening(input.openTime(), input.closeTime(),
                Boolean.TRUE.equals(input.is24h()), Boolean.TRUE.equals(input.endsNextDay()));
        return idempotency.execute("applyStoreRules", "PUT", "/api/v1/admin/stores/" + id + "/rules", key,
                input, StoreViews.AdminStoreEntry.class, () -> {
                    var store = stores.lock(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    ConfigurationEtags.requireMatch(ifMatch, StoreRules.etag(store));
                    var preview = computePreview(store, input.openTime(), input.closeTime(),
                            Boolean.TRUE.equals(input.is24h()), Boolean.TRUE.equals(input.endsNextDay()),
                            input.capacity(), input.slotPriceCent(), input.overtimePriceCent(),
                            input.overtimeCapCent());
                    if (!preview.previewHash().equals(input.previewHash())) {
                        throw new BusinessException(ErrorCode.PREVIEW_STALE);
                    }
                    if (!preview.canApply()) throw new BusinessException(ErrorCode.RULE_CONFLICT);
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    stores.update(null, new LambdaUpdateWrapper<Store>()
                            .eq(Store::getId, id)
                            .set(Store::getOpenTime, input.openTime())
                            .set(Store::getCloseTime, input.closeTime())
                            .set(Store::getIs24h, input.is24h())
                            .set(Store::getEndsNextDay, input.endsNextDay())
                            .set(Store::getCapacity, input.capacity())
                            .set(Store::getSlotPriceCent, input.slotPriceCent())
                            .set(Store::getOvertimePriceCent, input.overtimePriceCent())
                            .set(Store::getOvertimeCapCent, input.overtimeCapCent()));
                    // 只更新目标门店未售未来时段；已售时段与订单规则快照保持不变
                    slots.updateUnsoldRules(id, now, input.capacity(), input.slotPriceCent());
                    // 新规则下不再落在营业窗口内的未售时段：关闭而不是删除，保留历史
                    int closed = 0;
                    for (var slot : slots.futureUnsold(id, now)) {
                        if (!StoreRulePlanner.covered(input.openTime(), input.closeTime(),
                                Boolean.TRUE.equals(input.is24h()), Boolean.TRUE.equals(input.endsNextDay()),
                                slot.getStartAt(), slot.getEndAt())) {
                            closed += slots.closeUnsold(id, slot.getId(), now);
                        }
                    }
                    var updated = stores.selectById(id);
                    generation.generate(updated, now);
                    audit.append(merchantId, "STORE_RULES_APPLIED", "STORE", id, input.reason(),
                            Map.of("summary", "应用营业与计费规则",
                                    "before", ruleSnapshot(store), "after", ruleSnapshot(updated),
                                    "affectedUnsoldSlots", preview.affectedUnsoldSlots()));
                    return new IdempotencyService.Outcome<>(200,
                            StoreSupport.entry(stores.selectById(id)));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateStatus(String storeId,
            StoreRuleRequests.UpdateStatus input, String key, String ifMatch) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("updateStoreStatus", "PUT", "/api/v1/admin/stores/" + id + "/status", key,
                input, StoreViews.AdminStoreEntry.class, () -> {
                    var store = stores.lock(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    ConfigurationEtags.requireMatch(ifMatch, StoreRules.etag(store));
                    if ("DISABLED".equals(input.status())) {
                        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                        long visits = ops.inStoreVisits(id);
                        long orders = ops.unfulfilledOrders(id, now);
                        long operations = ops.pendingOperations(id);
                        if (visits > 0 || orders > 0 || operations > 0) {
                            throw new BusinessException(ErrorCode.STATE_CONFLICT);
                        }
                    }
                    stores.update(null, new LambdaUpdateWrapper<Store>().eq(Store::getId, id)
                            .set(Store::getStatus, input.status()));
                    audit.append(merchantId, "STORE_STATUS_CHANGED", "STORE", id, input.reason(),
                            Map.of("summary", "修改门店营业状态",
                                    "before", Map.of("status", store.getStatus()),
                                    "after", Map.of("status", input.status())));
                    return new IdempotencyService.Outcome<>(200, StoreSupport.entry(stores.selectById(id)));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('STORE_CONTROL')")
    public IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateAdmission(String storeId,
            StoreRuleRequests.UpdateAdmission input, String key, String ifMatch) {
        long id = Ids.parse(storeId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("updateStoreAdmission", "PUT", "/api/v1/admin/stores/" + id + "/admission",
                key, input, StoreViews.AdminStoreEntry.class, () -> {
                    var store = stores.lock(id);
                    if (store == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    ConfigurationEtags.requireMatch(ifMatch, StoreRules.etag(store));
                    if (!Boolean.TRUE.equals(input.paused())) {
                        // 恢复自动准入：当前读检查门店状态、消息链路、待复核异常与在场人数/容量
                        if (!"OPEN".equals(store.getStatus())) {
                            throw new BusinessException(ErrorCode.STATE_CONFLICT);
                        }
                        // 消息链路未知/异常时估计人数不可信，不能直接恢复为可自动准入
                        if (!"HEALTHY".equals(store.getMessageHealth())) {
                            throw new BusinessException(ErrorCode.OCCUPANCY_UNKNOWN);
                        }
                        if (ops.openReviewTasks(id) > 0) {
                            throw new BusinessException(ErrorCode.STATE_CONFLICT);
                        }
                        long inStore = ops.inStoreVisits(id);
                        int capacity = store.getCapacity() == null ? 0 : store.getCapacity();
                        if (capacity <= 0 || inStore >= capacity) {
                            throw new BusinessException(ErrorCode.OCCUPANCY_UNKNOWN);
                        }
                    }
                    stores.update(null, new LambdaUpdateWrapper<Store>().eq(Store::getId, id)
                            .set(Store::getAdmissionPaused, input.paused())
                            .set(Store::getPauseReason, Boolean.TRUE.equals(input.paused()) ? input.reason() : null));
                    audit.append(merchantId, "STORE_ADMISSION_CHANGED", "STORE", id, input.reason(),
                            Map.of("summary", "暂停或恢复门店新准入",
                                    "beforePaused", store.getAdmissionPaused(),
                                    "afterPaused", input.paused()));
                    return new IdempotencyService.Outcome<>(200, StoreSupport.entry(stores.selectById(id)));
                });
    }

    // ---------- 预览计算 ----------

    private StoreRuleViews.RulePreview computePreview(Store store, java.time.LocalTime open,
            java.time.LocalTime close, boolean is24h, boolean endsNextDay, int capacity, long slotPriceCent,
            long overtimePriceCent, long overtimeCapCent) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 承诺量/未售统计严格限定目标门店，避免同一商家多门店互相污染预览摘要
        List<com.hiking.gymsaas.store.model.entity.StoreSlot> committed =
                slots.futureCommitted(store.getId(), now);
        List<com.hiking.gymsaas.store.model.entity.StoreSlot> unsold = slots.futureUnsold(store.getId(), now);
        int maxReserved = slots.maxFutureReserved(store.getId(), now);

        boolean capacityBelow = capacity < maxReserved;
        boolean scheduleChanged = committed.stream().anyMatch(slot ->
                !StoreRulePlanner.covered(open, close, is24h, endsNextDay, slot.getStartAt(), slot.getEndAt())
                        || capacity < (slot.getReservedCount() == null ? 0 : slot.getReservedCount()));
        List<StoreRuleViews.Conflict> conflicts = new java.util.ArrayList<>();
        if (capacityBelow || scheduleChanged) {
            String reason = capacityBelow ? "CAPACITY_BELOW_COMMITTED" : "SCHEDULE_CHANGED";
            List<Long> orderIds = ops.futureOrderIds(store.getId(), now);
            if (orderIds.isEmpty()) conflicts.add(new StoreRuleViews.Conflict(null, reason));
            else orderIds.forEach(orderId -> conflicts.add(new StoreRuleViews.Conflict(orderId.toString(), reason)));
        }

        long affected = unsold.stream().filter(slot ->
                (slot.getCapacity() == null ? 0 : slot.getCapacity()) != capacity
                        || (slot.getPriceCent() == null ? 0 : slot.getPriceCent()) != slotPriceCent
                        || !StoreRulePlanner.covered(open, close, is24h, endsNextDay, slot.getStartAt(),
                                slot.getEndAt())).count();
        int uncovered = StoreRules.windowMinutes(open, close, is24h, endsNextDay) % StoreRules.SLOT_MINUTES;
        String hash = StoreRulePlanner.previewHash(store, open, close, is24h, endsNextDay, capacity,
                slotPriceCent, overtimePriceCent, overtimeCapCent, maxReserved, committed);
        return new StoreRuleViews.RulePreview(store.getId().toString(), hash, (int) affected, conflicts,
                uncovered, conflicts.isEmpty());
    }

    private Map<String, Object> ruleSnapshot(Store store) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("openTime", StoreRules.time(store.getOpenTime()));
        snapshot.put("closeTime", StoreRules.time(store.getCloseTime()));
        snapshot.put("is24h", store.getIs24h());
        snapshot.put("endsNextDay", store.getEndsNextDay());
        snapshot.put("capacity", store.getCapacity());
        snapshot.put("slotPriceCent", store.getSlotPriceCent());
        snapshot.put("overtimePriceCent", store.getOvertimePriceCent());
        snapshot.put("overtimeCapCent", store.getOvertimeCapCent());
        return snapshot;
    }
}
