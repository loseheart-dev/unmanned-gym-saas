package com.hiking.gymsaas.store.service;

import com.hiking.gymsaas.store.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;

/** 本地对象存储（M5 开发实现）；objectKey 与公开 URL 约定与生产对象存储保持一致。 */
@Service
public class LocalObjectStorage {
    private final Path root;
    private final String publicBaseUrl;

    public LocalObjectStorage(StorageProperties properties) {
        this.root = Paths.get(properties.getLocalRoot()).toAbsolutePath().normalize();
        this.publicBaseUrl = properties.getPublicBaseUrl();
    }

    public void save(String objectKey, byte[] bytes) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法对象键");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("写入图片文件失败", ex);
        }
    }

    public String publicUrl(String objectKey) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + "/" + objectKey;
    }
}
