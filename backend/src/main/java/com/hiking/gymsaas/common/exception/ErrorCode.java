package com.hiking.gymsaas.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有该操作权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在或不可访问"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持该请求方式"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "同一幂等键的请求内容不一致"),
    ETAG_MISMATCH(HttpStatus.PRECONDITION_FAILED, "配置已变化，请刷新后重试"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "缺少并发更新前置条件"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    PHONE_BINDING_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "手机号绑定能力未开通"),
    // 门店与规则（#9）
    INVALID_TIME_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "时间范围不合法"),
    PREVIEW_STALE(HttpStatus.CONFLICT, "规则预览已过期，请重新预览"),
    RULE_CONFLICT(HttpStatus.CONFLICT, "规则变更与已有承诺冲突"),
    SLOT_COMMITTED(HttpStatus.CONFLICT, "该时段已被承诺，不能修改"),
    OCCUPANCY_UNKNOWN(HttpStatus.CONFLICT, "当前在场人数无法确认容量安全"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的文件类型"),
    // 预约与支付（#10）
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "该时段已不可预约"),
    BOOKING_OVERLAP(HttpStatus.CONFLICT, "与本人已有的有效预约时间重叠"),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "订单支付时间已截止"),
    MERCHANT_BLOCKED(HttpStatus.FORBIDDEN, "该商家已限制本人预约"),
    BOOKING_RESTRICTED(HttpStatus.FORBIDDEN, "本人当前被限制在该商家预约"),
    OUTSTANDING_PAYMENT(HttpStatus.FORBIDDEN, "本人在该商家存在待缴费用"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂不可用");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
