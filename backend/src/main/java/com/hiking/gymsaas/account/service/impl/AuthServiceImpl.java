package com.hiking.gymsaas.account.service.impl;

import com.hiking.gymsaas.account.mapper.IdentityMapper;
import com.hiking.gymsaas.account.model.dto.AuthRequests;
import com.hiking.gymsaas.account.model.entity.CustomerUser;
import com.hiking.gymsaas.account.model.vo.AuthViews;
import com.hiking.gymsaas.account.service.AuthService;
import com.hiking.gymsaas.account.service.IdentityService;
import com.hiking.gymsaas.account.service.PasswordRules;
import com.hiking.gymsaas.account.service.PhoneCipher;
import com.hiking.gymsaas.account.service.WechatLoginClient;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.idempotency.IdempotencyService.Outcome;
import com.hiking.gymsaas.common.security.*;
import com.hiking.gymsaas.common.util.Hashes;
import com.hiking.gymsaas.operation.service.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!bootstrap")
public class AuthServiceImpl implements AuthService {
    private final IdentityMapper mapper;
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    private final JwtTokens tokens;
    private final AuthRateLimiter limiter;
    private final WechatLoginClient wechat;
    private final DevAccess dev;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final PhoneCipher phones;
    private final TransactionTemplate transactions;
    private final String dummyHash;

    public AuthServiceImpl(IdentityMapper mapper, IdentityService identities, PasswordEncoder passwords,
                           JwtTokens tokens, AuthRateLimiter limiter, WechatLoginClient wechat, DevAccess dev,
                           AuditService audit, IdempotencyService idempotency, PhoneCipher phones,
                           PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.identities = identities;
        this.passwords = passwords;
        this.tokens = tokens;
        this.limiter = limiter;
        this.wechat = wechat;
        this.dev = dev;
        this.audit = audit;
        this.idempotency = idempotency;
        this.phones = phones;
        transactions = new TransactionTemplate(manager);
        dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    @Override
    public AuthViews.AdminToken adminLogin(AuthRequests.AdminLogin request, String clientAddress) {
        limiter.check("admin-ip", clientAddress, 30, 60);
        limiter.rejectAtLimit("admin-failures", request.username(), 5);
        var user = mapper.adminByUsername(request.username());
        String hash = user == null ? dummyHash : user.getPasswordHash();
        // BCrypt cannot authenticate passwords longer than 72 UTF-8 bytes; never silently truncate them.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(request.password(), hash) || user == null) {
            limiter.check("admin-failures", request.username(), 5, 600);
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (!"ACTIVE".equals(user.getStatus())) throw new BusinessException(ErrorCode.FORBIDDEN);
        var identity = transactions.execute(status -> {
            var current = mapper.lockAdmin(user.getId());
            if (current == null || !Objects.equals(current.getPasswordHash(), hash)) {
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            }
            var merchant = mapper.lockMerchant(current.getMerchantId());
            if (!"ACTIVE".equals(current.getStatus()) || merchant == null || !"ACTIVE".equals(merchant.getStatus())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            var verified = IdentityServiceImpl.adminIdentity(current);
            audit.login(verified);
            return verified;
        });
        return new AuthViews.AdminToken(tokens.issue(identity), "Bearer", JwtTokens.EXPIRES_IN, adminView(identity));
    }

    @Override
    public AuthViews.CustomerToken wechatLogin(AuthRequests.WechatLogin request, String clientAddress) {
        limiter.check("wechat-ip", clientAddress, 30, 60);
        var identity = wechat.exchange(request.code());
        return customerToken(getOrCreate(identity.appId(), identity.openid()));
    }

    @Override
    public AuthViews.CustomerToken devLogin(AuthRequests.DevLogin request, String key, String clientAddress) {
        if (!dev.enabled()) throw new BusinessException(ErrorCode.NOT_FOUND);
        limiter.check("dev-ip", clientAddress, 30, 60);
        dev.verify(key);
        return customerToken(getOrCreate("dev:fixture", request.fixtureKey().name()));
    }

    private CustomerUser getOrCreate(String appId, String openid) {
        try {
            return transactions.execute(status -> {
                var existing = mapper.customerByWechat(appId, openid);
                if (existing != null) return existing;
                mapper.createCustomer(appId, openid);
                return mapper.customerByWechat(appId, openid);
            });
        } catch (DuplicateKeyException ex) {
            // Another login won the unique identity insert; its transaction must be read after ours rolled back.
            return mapper.customerByWechat(appId, openid);
        }
    }

    private AuthViews.CustomerToken customerToken(CustomerUser user) {
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BusinessException(ErrorCode.FORBIDDEN);
        var identity = identities.load(Identity.Type.CUSTOMER, user.getId());
        return new AuthViews.CustomerToken(tokens.issue(identity), "Bearer", JwtTokens.EXPIRES_IN,
                new AuthViews.Customer(user.getId().toString(), user.getNickname(), user.getPhoneVerifiedAt() != null));
    }

    @Override
    public AuthViews.Admin adminMe() { return adminView(CurrentIdentity.admin()); }

    private AuthViews.Admin adminView(Identity identity) {
        var user = mapper.admin(identity.id());
        return new AuthViews.Admin(Long.toString(identity.id()), identity.merchantId().toString(),
                user.getUsername(), identity.role(), identity.permissions());
    }

    @Override
    public AuthViews.CustomerProfile customerMe() {
        return profile(mapper.customerProfile(CurrentIdentity.customer().id()));
    }

    @Override
    public AuthViews.CustomerProfile renameCustomer(String key, AuthRequests.Nickname request) {
        long id = CurrentIdentity.customer().id();
        return idempotency.execute("renameCustomer", "PATCH", "/api/v1/app/me", key, request,
                AuthViews.CustomerProfile.class, () -> {
                    mapper.renameCustomer(id, request.nickname());
                    return new Outcome<>(200, profile(mapper.customerProfile(id)));
                }).data();
    }

    @Override
    public AuthViews.CustomerProfile bindPhone(String key, AuthRequests.PhoneBinding request) {
        long id = CurrentIdentity.customer().id();
        if (!phones.configured() || !dev.enabled()) {
            throw new BusinessException(ErrorCode.PHONE_BINDING_UNAVAILABLE);
        }
        var customer = mapper.customerProfile(id);
        if (customer == null || !"dev:fixture".equals(customer.getWxAppId())) {
            throw new BusinessException(ErrorCode.PHONE_BINDING_UNAVAILABLE);
        }
        if (!request.phoneCode().startsWith("mock-")) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        String digest = Hashes.sha256(request.phoneCode());
        return idempotency.execute("bindPhone", "POST", "/api/v1/app/me/phone-binding", key, digest,
                AuthViews.CustomerProfile.class, () -> {
                    try { mapper.consumeLocalPhoneCode(digest); }
                    catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.STATE_CONFLICT); }
                    // shortcut: fixture-only exchange, replace with the provider's one-time exchange when enabled.
                    String phone = String.format(Locale.ROOT, "138%08d", Long.parseLong(digest.substring(0, 8), 16) % 100_000_000);
                    mapper.setCustomerPhone(id, phones.encrypt(phone));
                    return new Outcome<>(200, profile(mapper.customerProfile(id)));
                }).data();
    }

    @Override
    public AuthViews.CustomerProfile unbindPhone(String key) {
        long id = CurrentIdentity.customer().id();
        return idempotency.execute("unbindPhone", "DELETE", "/api/v1/app/me/phone-binding", key, null,
                AuthViews.CustomerProfile.class, () -> {
                    mapper.setCustomerPhone(id, null);
                    return new Outcome<>(200, profile(mapper.customerProfile(id)));
                }).data();
    }

    private AuthViews.CustomerProfile profile(CustomerUser user) {
        if (user == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return new AuthViews.CustomerProfile(user.getId().toString(), user.getNickname(),
                phones.mask(user.getPhoneCiphertext()), user.getPhoneVerifiedAt() != null);
    }

    @Override
    public void changePassword(AuthRequests.ChangePassword request) {
        var identity = CurrentIdentity.admin();
        var admin = mapper.admin(identity.id());
        String hash = admin == null ? dummyHash : admin.getPasswordHash();
        if (request.oldPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(request.oldPassword(), hash) || admin == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        // 与员工创建/重置共用同一密码口径（12–128 字符、UTF-8 ≤72 字节、不得等于登录名）
        PasswordRules.validate(request.newPassword(), admin.getUsername());
        if (request.newPassword().equals(request.oldPassword())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String replacement = passwords.encode(request.newPassword());
        transactions.executeWithoutResult(status -> {
            identities.recheck(identity, true);
            mapper.changePassword(identity.id(), replacement);
            audit.append(identity.merchantId(), "ADMIN_PASSWORD_CHANGED", "ADMIN", identity.id(), "修改本人后台密码", Map.of());
        });
    }

    @Override
    public void logout() {
        var identity = CurrentIdentity.get();
        transactions.executeWithoutResult(status -> {
            identities.recheck(identity, true);
            int count = identity.type() == Identity.Type.ADMIN
                    ? mapper.logoutAdmin(identity.id(), identity.tokenVersion())
                    : mapper.logoutCustomer(identity.id(), identity.tokenVersion());
            if (count != 1) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            if (identity.type() == Identity.Type.ADMIN) {
                audit.append(identity.merchantId(), "ADMIN_LOGOUT", "ADMIN", identity.id(), "退出全部后台登录",
                        Map.of("summary", "全部已有后台令牌失效"));
            }
        });
    }
}
