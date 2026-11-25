package com.hiking.gymsaas.account.service;

import com.hiking.gymsaas.account.model.dto.StaffRequests;
import com.hiking.gymsaas.account.model.vo.StaffViews;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;

/** 员工管理（接口文档 §3.3–§3.8）。 */
public interface StaffService {
    PageResult<StaffViews.Staff> list(PageQuery query, String status);

    Entry detail(String staffId);

    IdempotencyService.Outcome<Entry> create(StaffRequests.Create input, String key);

    IdempotencyService.Outcome<Entry> updateRole(String staffId, StaffRequests.UpdateRole input,
                                                 String key, String ifMatch);

    IdempotencyService.Outcome<Entry> updateStatus(String staffId, StaffRequests.UpdateStatus input,
                                                   String key, String ifMatch);

    IdempotencyService.Outcome<com.hiking.gymsaas.account.model.vo.AuthViews.Accepted> resetPassword(
            String staffId, StaffRequests.ResetPassword input, String key);

    /** 视图 + 配置 ETag（供 HTTP ETag 与下一写请求的 If-Match 使用）。 */
    record Entry(StaffViews.Staff view, String etag) { }
}
