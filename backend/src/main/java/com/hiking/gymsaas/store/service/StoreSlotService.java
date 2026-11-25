package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.store.model.dto.StoreRuleRequests;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.model.vo.StoreViews;

/** 时段生成与单时段调整（§4.14、§4.15）。 */
public interface StoreSlotService {
    IdempotencyService.Outcome<StoreRuleViews.Accepted> generate(String storeId,
            StoreRuleRequests.GenerateSlots input, String key);

    IdempotencyService.Outcome<StoreViews.AdminSlot> adjustSlot(String storeId, String slotId,
            StoreRuleRequests.AdjustSlot input, String key, String ifMatch);
}
