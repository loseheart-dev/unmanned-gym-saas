package com.hiking.gymsaas.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 门店展示图片存储配置（M5 开发实现：本地目录 + 公开访问前缀）。
 *
 * <p>只存公开展示图，不存人脸或敏感资料；objectKey 仅本商家可用，
 * 生产可替换为受控对象存储而不用改业务代码。
 */
@Component
@ConfigurationProperties(prefix = "gym.storage")
public class StorageProperties {
    private String localRoot = "data/store-images";
    private String publicBaseUrl = "/media/store-images";
    private long maxImageBytes = 5L * 1024 * 1024;

    public String getLocalRoot() { return localRoot; }
    public void setLocalRoot(String localRoot) { this.localRoot = localRoot; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
}
