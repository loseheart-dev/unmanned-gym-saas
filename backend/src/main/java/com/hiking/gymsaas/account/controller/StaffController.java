package com.hiking.gymsaas.account.controller;

import com.hiking.gymsaas.account.model.dto.StaffRequests;
import com.hiking.gymsaas.account.model.vo.AuthViews;
import com.hiking.gymsaas.account.service.StaffService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.account.model.vo.StaffViews;
import com.hiking.gymsaas.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 员工管理（接口文档 §3.3–§3.8）；商家归属取 JWT，不接受请求体传入 merchantId。 */
@RestController
@Profile("!bootstrap")
@RequestMapping("/admin/staff")
public class StaffController {
    private final StaffService staff;

    public StaffController(StaffService staff) { this.staff = staff; }

    @GetMapping
    public ApiResponse<PageResult<StaffViews.Staff>> list(@Valid PageQuery page,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(staff.list(page, status));
    }

    @GetMapping("/{staffId}")
    public ResponseEntity<ApiResponse<StaffViews.Staff>> detail(@PathVariable String staffId) {
        var result = staff.detail(staffId);
        return ResponseEntity.ok().eTag(result.etag()).body(ApiResponse.ok(result.view()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StaffViews.Staff>> create(
            @Valid @RequestBody StaffRequests.Create input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = staff.create(input, key);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().view()));
    }

    @PatchMapping("/{staffId}")
    public ResponseEntity<ApiResponse<StaffViews.Staff>> updateRole(@PathVariable String staffId,
            @Valid @RequestBody StaffRequests.UpdateRole input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = staff.updateRole(staffId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().view()));
    }

    @PutMapping("/{staffId}/status")
    public ResponseEntity<ApiResponse<StaffViews.Staff>> updateStatus(@PathVariable String staffId,
            @Valid @RequestBody StaffRequests.UpdateStatus input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = staff.updateStatus(staffId, input, key, ifMatch);
        return ResponseEntity.status(result.status()).eTag(result.data().etag())
                .body(ApiResponse.ok(result.data().view()));
    }

    @PostMapping("/{staffId}/password-reset")
    public ApiResponse<AuthViews.Accepted> resetPassword(@PathVariable String staffId,
            @Valid @RequestBody StaffRequests.ResetPassword input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(staff.resetPassword(staffId, input, key).data());
    }
}
