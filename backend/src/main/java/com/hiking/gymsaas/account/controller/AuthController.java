package com.hiking.gymsaas.account.controller;

import com.hiking.gymsaas.account.model.dto.AuthRequests;
import com.hiking.gymsaas.account.model.vo.AuthViews;
import com.hiking.gymsaas.account.service.AuthService;
import com.hiking.gymsaas.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/admin/auth/login")
    public ApiResponse<AuthViews.AdminToken> adminLogin(@Valid @RequestBody AuthRequests.AdminLogin input, HttpServletRequest request) {
        return ApiResponse.ok(auth.adminLogin(input, request.getRemoteAddr()));
    }

    @PostMapping("/app/auth/wechat-login")
    public ApiResponse<AuthViews.CustomerToken> wechatLogin(@Valid @RequestBody AuthRequests.WechatLogin input, HttpServletRequest request) {
        return ApiResponse.ok(auth.wechatLogin(input, request.getRemoteAddr()));
    }

    @GetMapping("/admin/me")
    public ApiResponse<AuthViews.Admin> adminMe() { return ApiResponse.ok(auth.adminMe()); }

    @GetMapping("/app/me")
    public ApiResponse<AuthViews.CustomerProfile> customerMe() { return ApiResponse.ok(auth.customerMe()); }

    @PatchMapping("/app/me")
    public ApiResponse<AuthViews.CustomerProfile> rename(@RequestHeader("Idempotency-Key") String key,
                                                          @Valid @RequestBody AuthRequests.Nickname input) {
        return ApiResponse.ok(auth.renameCustomer(key, input));
    }

    @PostMapping("/app/me/phone-binding")
    public ApiResponse<AuthViews.CustomerProfile> bindPhone(@RequestHeader("Idempotency-Key") String key,
                                                             @Valid @RequestBody AuthRequests.PhoneBinding input) {
        return ApiResponse.ok(auth.bindPhone(key, input));
    }

    @DeleteMapping("/app/me/phone-binding")
    public ApiResponse<AuthViews.CustomerProfile> unbindPhone(@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(auth.unbindPhone(key));
    }

    @PostMapping("/admin/me/password")
    public ApiResponse<AuthViews.Accepted> changePassword(@Valid @RequestBody AuthRequests.ChangePassword input) {
        auth.changePassword(input);
        return ApiResponse.ok(new AuthViews.Accepted(true));
    }

    @PostMapping({"/admin/auth/logout", "/app/auth/logout"})
    public ApiResponse<AuthViews.Accepted> logout() {
        auth.logout();
        return ApiResponse.ok(new AuthViews.Accepted(true));
    }
}
