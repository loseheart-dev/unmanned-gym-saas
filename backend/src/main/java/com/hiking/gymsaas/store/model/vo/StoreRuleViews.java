package com.hiking.gymsaas.store.model.vo;

import java.util.List;

/** §4.8–§4.15 视图。 */
public final class StoreRuleViews {
    private StoreRuleViews() { }

    /** 4.8 对外响应：字段与 OpenAPI 一致，返回 objectKey。 */
    public record UploadResult(String objectKey, String url, String contentType, long sizeBytes) { }

    /**
     * 4.8 幂等重放快照：通用快照层把 objectKey 视为敏感键，禁止进入重放快照。
     * 因此用专门的重放实现，快照字段用 imageKey 保存同一个对象键，重放时由 Controller 映射回 objectKey。
     */
    public record UploadSnapshot(String imageKey, String url, String contentType, long sizeBytes) { }

    /** 4.9 冲突项（已售订单）。 */
    public record Conflict(String orderId, String reason) { }

    /** 4.9 预览结果；previewHash 是方案+当前承诺量摘要，不是授权凭据。 */
    public record RulePreview(String storeId, String previewHash, int affectedUnsoldSlots,
                              List<Conflict> conflicts, int uncoveredMinutes, boolean canApply) { }

    /** 4.14 受理结果。 */
    public record Accepted(boolean accepted) { }
}
