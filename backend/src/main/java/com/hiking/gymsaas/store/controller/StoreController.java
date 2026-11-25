package com.hiking.gymsaas.store.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.web.ApiResponse;
import com.hiking.gymsaas.store.model.dto.StoreRequests;
import com.hiking.gymsaas.store.model.dto.StoreRuleRequests;
import com.hiking.gymsaas.store.model.vo.StoreRuleViews;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.StoreImageService;
import com.hiking.gymsaas.store.service.StoreRuleService;
import com.hiking.gymsaas.store.service.StoreService;
import com.hiking.gymsaas.store.service.StoreSlotService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 后台门店管理（§4.4–§4.15）；商家归属来自 JWT。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/admin/stores")
public class StoreController {
    private final StoreService stores;
    private final StoreImageService images;
    private final StoreRuleService rules;
    private final StoreSlotService slots;

    public StoreController(StoreService stores, StoreImageService images, StoreRuleService rules,
                           StoreSlotService slots) {
        this.stores = stores;
        this.images = images;
        this.rules = rules;
        this.slots = slots;
    }

    @GetMapping
    public ApiResponse<PageResult<StoreViews.AdminStore>> list(@Valid PageQuery page,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(stores.list(page, status));
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> detail(@PathVariable String storeId) {
        var result = stores.detail(storeId);
        return ResponseEntity.ok().eTag(result.etag()).body(ApiResponse.ok(result.store()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> create(
            @Valid @RequestBody StoreRequests.Create input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = stores.create(input, key);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().store()));
    }

    @PatchMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> updateProfile(@PathVariable String storeId,
            @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = stores.updateProfile(storeId, body, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().store()));
    }

    @GetMapping("/{storeId}/slots")
    public ApiResponse<StoreViews.AdminSlotList> slots(@PathVariable String storeId,
                                                       @RequestParam String date) {
        return ApiResponse.ok(stores.slots(storeId, date));
    }

    // ---------- 4.8 图片上传 ----------

    @PostMapping("/{storeId}/images")
    public ResponseEntity<ApiResponse<StoreRuleViews.UploadResult>> uploadImage(@PathVariable String storeId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = images.upload(storeId, file, key);
        var snapshot = result.data();
        // 专门的重放映射：快照保存 imageKey（避免 objectKey 被通用快照层脱敏），对外仍按 OpenAPI 返回 objectKey
        var response = new StoreRuleViews.UploadResult(snapshot.imageKey(), snapshot.url(),
                snapshot.contentType(), snapshot.sizeBytes());
        return ResponseEntity.status(result.status()).body(ApiResponse.ok(response));
    }

    // ---------- 4.9 规则预览 ----------

    @PostMapping("/{storeId}/rule-preview")
    public ApiResponse<StoreRuleViews.RulePreview> previewRules(@PathVariable String storeId,
            @Valid @RequestBody StoreRuleRequests.RulePreview input) {
        return ApiResponse.ok(rules.preview(storeId, input));
    }

    // ---------- 4.10 应用规则 ----------

    @PutMapping("/{storeId}/rules")
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> applyRules(@PathVariable String storeId,
            @Valid @RequestBody StoreRuleRequests.ApplyRules input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = rules.applyRules(storeId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().store()));
    }

    // ---------- 4.11 营业状态 ----------

    @PutMapping("/{storeId}/status")
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> updateStatus(@PathVariable String storeId,
            @Valid @RequestBody StoreRuleRequests.UpdateStatus input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = rules.updateStatus(storeId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().store()));
    }

    // ---------- 4.12 准入暂停/恢复 ----------

    @PutMapping("/{storeId}/admission")
    public ResponseEntity<ApiResponse<StoreViews.AdminStore>> updateAdmission(@PathVariable String storeId,
            @Valid @RequestBody StoreRuleRequests.UpdateAdmission input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = rules.updateAdmission(storeId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().store()));
    }

    // ---------- 4.14 补齐未来七天时段 ----------

    @PostMapping("/{storeId}/slot-generation")
    public ResponseEntity<ApiResponse<StoreRuleViews.Accepted>> generateSlots(@PathVariable String storeId,
            @Valid @RequestBody StoreRuleRequests.GenerateSlots input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = slots.generate(storeId, input, key);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(result.data()));
    }

    // ---------- 4.15 调整未售时段 ----------

    @PatchMapping("/{storeId}/slots/{slotId}")
    public ResponseEntity<ApiResponse<StoreViews.AdminSlot>> adjustSlot(@PathVariable String storeId,
            @PathVariable String slotId,
            @Valid @RequestBody StoreRuleRequests.AdjustSlot input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = slots.adjustSlot(storeId, slotId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data()));
    }
}
