package com.hiking.gymsaas.store.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.web.ApiResponse;
import com.hiking.gymsaas.store.model.dto.StoreRequests;
import com.hiking.gymsaas.store.model.vo.StoreViews;
import com.hiking.gymsaas.store.service.StoreService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台门店管理（§4.4–§4.7、§4.13）；商家归属来自 JWT。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/admin/stores")
public class StoreController {
    private final StoreService stores;

    public StoreController(StoreService stores) { this.stores = stores; }

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
}
