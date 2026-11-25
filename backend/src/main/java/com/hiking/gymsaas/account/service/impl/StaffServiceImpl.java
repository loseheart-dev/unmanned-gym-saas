package com.hiking.gymsaas.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hiking.gymsaas.account.mapper.StaffMapper;
import com.hiking.gymsaas.account.model.dto.StaffRequests;
import com.hiking.gymsaas.account.model.entity.AdminUser;
import com.hiking.gymsaas.account.model.vo.AuthViews;
import com.hiking.gymsaas.account.model.vo.StaffViews;
import com.hiking.gymsaas.account.service.PasswordRules;
import com.hiking.gymsaas.account.service.StaffRules;
import com.hiking.gymsaas.account.service.StaffService;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.page.PageQuery;
import com.hiking.gymsaas.common.page.PageResult;
import com.hiking.gymsaas.common.security.CurrentIdentity;
import com.hiking.gymsaas.common.util.Ids;
import com.hiking.gymsaas.common.web.ConfigurationEtags;
import com.hiking.gymsaas.operation.service.AuditService;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 员工管理实现（接口文档 §3.3–§3.8）。
 *
 * <p>商家归属只来自 JWT（CurrentIdentity）；所有查询/写入由商家隔离拦截器按当前商家过滤。
 * 角色/权限/状态/密码变更都递增 token_version，旧 JWT 立即失效；最后一位启用管理员不可撤销或停用。
 */
@Service
@Profile("!bootstrap")
public class StaffServiceImpl implements StaffService {
    private final StaffMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public StaffServiceImpl(StaffMapper mapper, PasswordEncoder passwordEncoder,
                            IdempotencyService idempotency, AuditService audit) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public PageResult<StaffViews.Staff> list(PageQuery query, String status) {
        var filter = new LambdaQueryWrapper<AdminUser>()
                .eq(status != null && !status.isBlank(), AdminUser::getStatus, status)
                .orderByAsc(AdminUser::getId);
        var page = mapper.selectPage(query.toPage(), filter);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::view).toList());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public Entry detail(String staffId) {
        var staff = mapper.tenantStaff(Ids.parse(staffId));
        if (staff == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return entry(staff);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<Entry> create(StaffRequests.Create input, String key) {
        StaffRules.validatePermissions(input.role(), input.permissions());
        PasswordRules.validate(input.initialPassword(), input.username());
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("createStaff", "POST", "/api/v1/admin/staff", key,
                StaffRules.createFingerprint(input), Entry.class, () -> {
            var staff = new AdminUser();
            staff.setMerchantId(merchantId);
            staff.setUsername(input.username());
            staff.setPasswordHash(passwordEncoder.encode(input.initialPassword()));
            staff.setRole(input.role());
            staff.setPermissions(StaffRules.ROLE_ADMIN.equals(input.role()) ? List.of() : List.copyOf(input.permissions()));
            staff.setStatus(StaffRules.STATUS_ACTIVE);
            staff.setTokenVersion(0L);
            mapper.insert(staff);
            audit.append(merchantId, "STAFF_CREATED", "ADMIN", staff.getId(), input.reason(),
                    Map.of("summary", "创建员工账号",
                            "after", Map.of("username", input.username(), "role", input.role(),
                                    "permissions", staff.getPermissions())));
            return new IdempotencyService.Outcome<>(201, entry(mapper.tenantStaff(staff.getId())));
        });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<Entry> updateRole(String staffId, StaffRequests.UpdateRole input,
                                                        String key, String ifMatch) {
        StaffRules.validatePermissions(input.role(), input.permissions());
        if (ifMatch == null || ifMatch.isBlank()) throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
        long id = Ids.parse(staffId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("updateStaffRole", "PATCH", "/api/v1/admin/staff/" + id, key,
                StaffRules.roleFingerprint(input, ifMatch), Entry.class, () -> {
                    var activeAdmins = mapper.lockActiveAdmins();
                    var staff = mapper.lockStaff(id);
                    if (staff == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    ConfigurationEtags.requireMatch(ifMatch, StaffRules.etag(staff.getRole(),
                            staff.getPermissions(), staff.getStatus()));
                    boolean removesActiveAdmin = StaffRules.isActiveAdmin(staff.getRole(), staff.getStatus())
                            && !StaffRules.ROLE_ADMIN.equals(input.role());
                    if (removesActiveAdmin && activeAdmins.size() <= 1) {
                        throw new BusinessException(ErrorCode.STATE_CONFLICT);
                    }
                    List<String> permissions = StaffRules.ROLE_ADMIN.equals(input.role())
                            ? List.of() : List.copyOf(input.permissions());
                    if (mapper.updateRole(id, input.role(), toJsonArray(permissions)) != 1) {
                        throw new BusinessException(ErrorCode.STATE_CONFLICT);
                    }
                    audit.append(merchantId, "STAFF_ROLE_CHANGED", "ADMIN", id, input.reason(),
                            Map.of("summary", "调整员工角色与权限",
                                    "before", Map.of("role", staff.getRole(),
                                            "permissions", staff.getPermissions() == null ? List.of() : staff.getPermissions()),
                                    "after", Map.of("role", input.role(), "permissions", permissions)));
                    return new IdempotencyService.Outcome<>(200, entry(mapper.tenantStaff(id)));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<Entry> updateStatus(String staffId, StaffRequests.UpdateStatus input,
                                                          String key, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
        long id = Ids.parse(staffId);
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("updateStaffStatus", "PUT", "/api/v1/admin/staff/" + id + "/status", key,
                StaffRules.statusFingerprint(input, ifMatch), Entry.class, () -> {
                    var activeAdmins = mapper.lockActiveAdmins();
                    var staff = mapper.lockStaff(id);
                    if (staff == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    ConfigurationEtags.requireMatch(ifMatch, StaffRules.etag(staff.getRole(),
                            staff.getPermissions(), staff.getStatus()));
                    boolean disablesActiveAdmin = StaffRules.STATUS_DISABLED.equals(input.status())
                            && StaffRules.isActiveAdmin(staff.getRole(), staff.getStatus());
                    if (disablesActiveAdmin && activeAdmins.size() <= 1) {
                        throw new BusinessException(ErrorCode.STATE_CONFLICT);
                    }
                    if (mapper.updateStatus(id, input.status()) != 1) {
                        throw new BusinessException(ErrorCode.STATE_CONFLICT);
                    }
                    audit.append(merchantId, "STAFF_STATUS_CHANGED", "ADMIN", id, input.reason(),
                            Map.of("summary", "启停员工账号",
                                    "before", Map.of("status", staff.getStatus()),
                                    "after", Map.of("status", input.status())));
                    return new IdempotencyService.Outcome<>(200, entry(mapper.tenantStaff(id)));
                });
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public IdempotencyService.Outcome<AuthViews.Accepted> resetPassword(String staffId,
                                                                        StaffRequests.ResetPassword input,
                                                                        String key) {
        long id = Ids.parse(staffId);
        var target = mapper.tenantStaff(id);
        if (target == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        PasswordRules.validate(input.newPassword(), target.getUsername());
        long merchantId = CurrentIdentity.merchantId();
        return idempotency.execute("resetStaffPassword", "POST",
                "/api/v1/admin/staff/" + id + "/password-reset", key,
                StaffRules.resetFingerprint(input), AuthViews.Accepted.class, () -> {
                    var staff = mapper.lockStaff(id);
                    if (staff == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                    if (mapper.resetPassword(id, passwordEncoder.encode(input.newPassword())) != 1) {
                        throw new BusinessException(ErrorCode.STATE_CONFLICT);
                    }
                    // 审计与幂等快照都不保存密码明文/哈希
                    audit.append(merchantId, "STAFF_PASSWORD_RESET", "ADMIN", id, input.reason(),
                            Map.of("summary", "管理员重置员工密码", "target", staff.getUsername()));
                    return new IdempotencyService.Outcome<>(200, new AuthViews.Accepted(true));
                });
    }

    private Entry entry(AdminUser staff) {
        return new Entry(view(staff), StaffRules.etag(staff.getRole(), staff.getPermissions(), staff.getStatus()));
    }

    private StaffViews.Staff view(AdminUser staff) {
        return new StaffViews.Staff(staff.getId().toString(), staff.getUsername(), staff.getRole(),
                staff.getPermissions() == null ? List.of() : staff.getPermissions(), staff.getStatus(),
                staff.getCreatedAt() == null ? null : staff.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    private String toJsonArray(List<String> permissions) {
        if (permissions.isEmpty()) return "[]";
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < permissions.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append('"').append(permissions.get(i)).append('"');
        }
        return builder.append(']').toString();
    }
}
