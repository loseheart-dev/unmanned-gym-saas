package com.hiking.gymsaas.account.service;

import com.hiking.gymsaas.account.model.dto.AuthRequests;
import com.hiking.gymsaas.account.model.vo.AuthViews;

public interface AuthService {
    AuthViews.AdminToken adminLogin(AuthRequests.AdminLogin request, String clientAddress);
    AuthViews.CustomerToken wechatLogin(AuthRequests.WechatLogin request, String clientAddress);
    AuthViews.CustomerToken devLogin(AuthRequests.DevLogin request, String key, String clientAddress);
    AuthViews.Admin adminMe();
    AuthViews.CustomerProfile customerMe();
    AuthViews.CustomerProfile renameCustomer(String key, AuthRequests.Nickname request);
    AuthViews.CustomerProfile bindPhone(String key, AuthRequests.PhoneBinding request);
    AuthViews.CustomerProfile unbindPhone(String key);
    void changePassword(AuthRequests.ChangePassword request);
    void logout();
}
