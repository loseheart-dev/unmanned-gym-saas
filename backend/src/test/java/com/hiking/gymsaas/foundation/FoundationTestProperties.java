package com.hiking.gymsaas.foundation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

final class FoundationTestProperties {
    static final String DEV_KEY = "foundation-development-key-at-least-32-bytes";
    private FoundationTestProperties() { }

    static void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("FOUNDATION_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/hiking_foundation_test(?:\\?.*)?")) {
            throw new IllegalStateException("Foundation integration tests require an isolated hiking_foundation_test database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "foundation_app");
        registry.add("spring.datasource.password", () -> "foundation-test-only");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> System.getenv("FOUNDATION_REDIS_PORT"));
        registry.add("app.security.jwt-secret", () -> Base64.getEncoder().encodeToString(
                "foundation-integration-key-32-bytes".getBytes(StandardCharsets.UTF_8)));
        registry.add("app.security.dev.enabled", () -> "true");
        registry.add("app.security.dev.key", () -> DEV_KEY);
        registry.add("app.wechat.phone-cipher-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
}
