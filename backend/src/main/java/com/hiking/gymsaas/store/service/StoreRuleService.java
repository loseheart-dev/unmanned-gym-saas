package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.store.model.dto.StoreRuleRequests;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.model.vo.StoreViews;

/** 门店规则与营业状态（§4.9–§4.12）。 */
public interface StoreRuleService {
    StoreRuleViews.RulePreview preview(String storeId, StoreRuleRequests.RulePreview input);

    IdempotencyService.Outcome<StoreViews.AdminStoreEntry> applyRules(String storeId,
            StoreRuleRequests.ApplyRules input, String key, String ifMatch);

    IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateStatus(String storeId,
            StoreRuleRequests.UpdateStatus input, String key, String ifMatch);

    IdempotencyService.Outcome<StoreViews.AdminStoreEntry> updateAdmission(String storeId,
            StoreRuleRequests.UpdateAdmission input, String key, String ifMatch);
}
