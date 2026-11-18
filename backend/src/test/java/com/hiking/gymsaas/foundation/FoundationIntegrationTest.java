package com.hiking.gymsaas.foundation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiking.gymsaas.account.mapper.MerchantMapper;
import com.hiking.gymsaas.account.service.IdentityService;
import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.idempotency.IdempotencyService;
import com.hiking.gymsaas.common.security.AuthRateLimiter;
import com.hiking.gymsaas.common.security.CurrentIdentity;
import com.hiking.gymsaas.common.security.Identity;
import com.hiking.gymsaas.operation.service.AuditService;
import com.hiking.gymsaas.store.model.entity.Store;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "FOUNDATION_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoundationIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { FoundationTestProperties.configure(registry); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired IdentityService identities;
    @Autowired IdempotencyService idempotency;
    @Autowired MerchantMapper merchants;
    @Autowired AuditService audit;
    @Autowired AuthRateLimiter limiter;
    @Autowired SqlSessionFactory sessions;
    @Autowired SqlSessionTemplate sql;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    private String passwordHash;
    private String prefix;
    private long merchantA, merchantB, adminA, adminB, operator;
    private String tokenA, tokenB;
    private ProbeMapper probe;
    private static final String PASSWORD = "foundation-test-password";

    @BeforeAll void registerProbeMapper() {
        passwordHash = passwords.encode(PASSWORD);
        sessions.getConfiguration().addMapper(ProbeMapper.class);
        probe = sql.getMapper(ProbeMapper.class);
    }

    @BeforeEach void fixture() throws Exception {
        prefix = UUID.randomUUID().toString().substring(0, 12);
        merchantA = insertMerchant(prefix + "A");
        merchantB = insertMerchant(prefix + "B");
        adminA = insertAdmin(merchantA, "A", "ADMIN", "[]");
        adminB = insertAdmin(merchantB, "B", "ADMIN", "[]");
        operator = insertAdmin(merchantA, "operator", "OPERATOR", "[\"AUDIT_READ\",\"ROLE_ADMIN\",\"unknown\"]");
        tokenA = login("A");
        tokenB = login("B");
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void loginUsesBcryptAndRejectsUnknownFieldsDuplicateFieldsAndWrongPasswords() throws Exception {
        mvc.perform(post("/admin/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", prefix + "A", "password", "wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/admin/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", prefix + "A", "password", "长".repeat(25)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/auth/login").contentType("application/json")
                .content("{\"username\":\"test\",\"password\":\"test\",\"merchantId\":\"999\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/auth/login").contentType("application/json")
                .content("{\"username\":\"first\",\"username\":\"second\",\"password\":\"test\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.merchantId").value(Long.toString(merchantA)))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA + "tampered"))).andExpect(status().isUnauthorized());
    }

    @Test
    void accountMerchantAndVersionChangesRevokeExistingTokens() throws Exception {
        jdbc.update("UPDATE merchant SET status='DISABLED' WHERE id=?", merchantA);
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE merchant SET status='ACTIVE' WHERE id=?", merchantA);
        jdbc.update("UPDATE admin_user SET status='DISABLED' WHERE id=?", adminA);
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE admin_user SET status='ACTIVE',token_version=token_version+1 WHERE id=?", adminA);
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA))).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenB))).andExpect(status().isOk());
    }

    @Test
    void logoutRevokesAllSessionsAndIsAuditedWithoutKeepingTokens() throws Exception {
        String second = login("A");
        mvc.perform(post("/admin/auth/logout").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        mvc.perform(get("/admin/me").header("Authorization", bearer(second))).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/auth/logout").header("Authorization", bearer(tokenA))).andExpect(status().isUnauthorized());
        assertEquals(1, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='ADMIN_LOGOUT'", merchantA));
        assertEquals(0, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
    }

    @Test
    void developmentCustomersCannotCallAdminRoutesAndAdminTokensCannotCallCustomerRoutes() throws Exception {
        String customer = customerLogin("CUSTOMER_A");
        mvc.perform(get("/app/me").header("Authorization", bearer(customer))).andExpect(status().isOk());
        mvc.perform(get("/admin/me").header("Authorization", bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get("/app/me").header("Authorization", bearer(tokenA))).andExpect(status().isForbidden());
        mvc.perform(post("/dev/auth/customer-login").header("X-Dev-Key", "wrong")
                .contentType("application/json").content("{\"fixtureKey\":\"CUSTOMER_A\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/dev/auth/customer-login").header("X-Dev-Key", FoundationTestProperties.DEV_KEY)
                .contentType("application/json").content("{\"fixtureKey\":\"ANY_USER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/app/auth/wechat-login").contentType("application/json").content("{\"code\":\"test\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void customerLogoutRevokesEveryExistingCustomerToken() throws Exception {
        String first = customerLogin("CUSTOMER_A");
        String second = customerLogin("CUSTOMER_A");
        mvc.perform(post("/app/auth/logout").header("Authorization", bearer(first))).andExpect(status().isOk());
        mvc.perform(get("/app/me").header("Authorization", bearer(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/app/me").header("Authorization", bearer(second))).andExpect(status().isUnauthorized());
        mvc.perform(get("/app/me").header("Authorization", bearer(customerLogin("CUSTOMER_A")))).andExpect(status().isOk());
    }

    @Test
    void customerProfileWritesReplayWithoutReusingThePhoneCode() throws Exception {
        String token = customerLogin("CUSTOMER_A");
        String otherToken = customerLogin("CUSTOMER_B");
        String path = "/app/me/phone-binding";
        String name = "Nickname " + prefix;
        var rename = patch("/app/me").header("Authorization", bearer(token)).header("Idempotency-Key", prefix + "-rename")
                .contentType("application/json").content(json.writeValueAsString(Map.of("nickname", name)));
        mvc.perform(rename).andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(name));
        mvc.perform(rename).andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(name));
        mvc.perform(patch("/app/me").header("Authorization", bearer(token)).header("Idempotency-Key", prefix + "-rename")
                .contentType("application/json").content("{\"nickname\":\"different\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        String body = json.writeValueAsString(Map.of("phoneCode", "mock-" + prefix));
        var binding = post(path).header("Authorization", bearer(token)).header("Idempotency-Key", prefix + "-bind")
                .contentType("application/json").content(body);
        var result = mvc.perform(binding).andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneVerified").value(true))
                .andExpect(jsonPath("$.data.phoneMasked").isString()).andExpect(jsonPath("$.data.phoneCiphertext").doesNotHaveJsonPath())
                .andReturn();
        var profile = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(profile.path("phoneMasked").asText().matches("138\\*{4}\\d{4}"));
        long id = profile.path("id").asLong();
        assertTrue(jdbc.queryForObject("SELECT phone_ciphertext FROM customer_user WHERE id=?", String.class, id).startsWith("v1:"));
        mvc.perform(binding).andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneMasked").value(profile.path("phoneMasked").asText()));
        mvc.perform(post(path).header("Authorization", bearer(otherToken)).header("Idempotency-Key", prefix + "-reuse")
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(delete(path).header("Authorization", bearer(token)).header("Idempotency-Key", prefix + "-unbind"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneVerified").value(false));
        assertNull(jdbc.queryForObject("SELECT phone_ciphertext FROM customer_user WHERE id=?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT phone_verified_at FROM customer_user WHERE id=?", String.class, id));
    }

    @Test
    void changingAnAdminPasswordRevokesOldTokensAndSavesOnlyBcrypt() throws Exception {
        String replacement = "new-foundation-test-password";
        String url = "/admin/me/password";
        mvc.perform(post(url).header("Authorization", bearer(tokenA)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", bearer(tokenA)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", replacement))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        mvc.perform(get("/admin/me").header("Authorization", bearer(tokenA))).andExpect(status().isUnauthorized());
        String hash = jdbc.queryForObject("SELECT password_hash FROM admin_user WHERE id=?", String.class, adminA);
        assertTrue(passwords.matches(replacement, hash));
        assertFalse(passwords.matches(PASSWORD, hash));
    }

    @Test
    void adminLoginLimitCountsFailuresAndBlocksAtFive() throws Exception {
        var invalid = post("/admin/auth/login").with(r -> { r.setRemoteAddr(prefix); return r; }).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", prefix + "A", "password", "wrong-password")));
        for (int i = 0; i < 5; i++) mvc.perform(invalid).andExpect(status().isUnauthorized());
        mvc.perform(invalid).andExpect(status().isTooManyRequests());
    }

    @Test
    void databasePermissionsCannotEscalateOperatorsAndAuthoritiesAreReloaded() throws Exception {
        String token = login("operator");
        mvc.perform(get("/admin/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions.length()").value(1));
        mvc.perform(get("/admin/audit-events").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(patch("/admin/merchant").header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"name\":\"forbidden\",\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE admin_user SET permissions=JSON_ARRAY() WHERE id=?", operator);
        mvc.perform(get("/admin/audit-events").header("Authorization", bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void renamingAtomicallyCommitsAnIdempotentResultAndOneAuditEvent() throws Exception {
        String etag = merchantEtag(tokenA);
        var first = mvc.perform(rename(tokenA, "rename-key-001", etag, "Updated", "manual change"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Updated"))
                .andExpect(header().exists("ETag")).andReturn();
        mvc.perform(rename(tokenA, "rename-key-001", etag, "Updated", "manual change"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", first.getResponse().getHeader("ETag")));
        mvc.perform(rename(tokenA, "rename-key-001", etag, "Changed request", "manual change"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
        assertEquals(1, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='MERCHANT_NAME_CHANGED'", merchantA));
        assertEquals(prefix + "B", jdbc.queryForObject("SELECT name FROM merchant WHERE id=?", String.class, merchantB));
        String snapshot = jdbc.queryForObject("SELECT result_snapshot FROM api_request WHERE principal_id=?", String.class, adminA);
        assertFalse(snapshot.contains(tokenA));
        assertFalse(snapshot.contains("password"));
        String auditRequest = jdbc.queryForObject("SELECT request_id FROM business_event WHERE merchant_id=? AND event_type='MERCHANT_NAME_CHANGED'", String.class, merchantA);
        assertEquals(first.getResponse().getHeader("X-Request-Id"), auditRequest);
    }

    @Test
    void missingHeadersStaleEtagsAndForbiddenOwnerFieldsLeaveNoWrite() throws Exception {
        String etag = merchantEtag(tokenA);
        mvc.perform(rename(tokenA, null, etag, "bad", "test")).andExpect(status().isBadRequest());
        mvc.perform(rename(tokenA, "short", etag, "bad", "test")).andExpect(status().isBadRequest());
        mvc.perform(rename(tokenA, "missing-etag", null, "bad", "test")).andExpect(status().isPreconditionRequired());
        mvc.perform(rename(tokenA, "stale-etag-001", "\"stale\"", "bad", "test"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/admin/merchant").header("Authorization", bearer(tokenA)).header("Idempotency-Key", "injected-owner")
                .header("If-Match", etag).contentType("application/json")
                .content("{\"name\":\"bad\",\"reason\":\"test\",\"merchantId\":\"" + merchantB + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
        assertEquals(prefix + "A", jdbc.queryForObject("SELECT name FROM merchant WHERE id=?", String.class, merchantA));
    }

    @Test
    void headerAndQueryMerchantIdsCannotChangeTheAuthenticatedMerchant() throws Exception {
        mvc.perform(get("/admin/merchant").header("Authorization", bearer(tokenA)).header("X-Merchant-Id", merchantB)
                .param("merchantId", Long.toString(merchantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(Long.toString(merchantA)));
        mvc.perform(rename(tokenA, "owner-spoof-key", merchantEtag(tokenA), "Still A", "test")
                .header("X-Merchant-Id", merchantB).param("merchantId", Long.toString(merchantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(Long.toString(merchantA)));
        assertEquals(prefix + "B", jdbc.queryForObject("SELECT name FROM merchant WHERE id=?", String.class, merchantB));
    }

    @Test
    void actualHttpLoginRenameReplayAuditAndLogoutWorkUnderTheConfiguredContextPath() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + port + "/api/v1";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/admin/me")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", prefix + "A", "password", PASSWORD))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertEquals("no-store", login.headers().firstValue("Cache-Control").orElseThrow());
            var loginData = json.readTree(login.body()).path("data");
            assertEquals(7200, loginData.path("expiresIn").asInt());
            assertFalse(loginData.has("refreshToken"));
            String token = loginData.path("accessToken").asText();
            var merchant = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/merchant"))
                    .header("Authorization", bearer(token)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, merchant.statusCode());
            assertEquals(Long.toString(merchantA), json.readTree(merchant.body()).path("data").path("id").asText());
            var patch = HttpRequest.newBuilder(URI.create(base + "/admin/merchant"))
                    .header("Authorization", bearer(token)).header("Content-Type", "application/json")
                    .header("Idempotency-Key", "http-rename-key").header("If-Match", merchant.headers().firstValue("ETag").orElseThrow())
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"name\":\"HTTP verified\",\"reason\":\"test\"}")).build();
            var first = client.send(patch, HttpResponse.BodyHandlers.ofString());
            var replay = client.send(patch, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, first.statusCode());
            assertEquals(200, replay.statusCode());
            assertEquals(json.readTree(first.body()).path("data"), json.readTree(replay.body()).path("data"));
            assertNotEquals(first.headers().firstValue("X-Request-Id"), replay.headers().firstValue("X-Request-Id"));
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/audit-events"))
                    .header("Authorization", bearer(token)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, events.statusCode());
            assertTrue(json.readTree(events.body()).path("data").path("items").findValuesAsText("eventType").contains("MERCHANT_NAME_CHANGED"));
            assertEquals(1, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='MERCHANT_NAME_CHANGED'", merchantA));
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/admin/auth/logout"))
                    .header("Authorization", bearer(token)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/admin/me"))
                    .header("Authorization", bearer(token)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void auditReadsAreTenantScopedAndValidateIdsAndTimeRanges() throws Exception {
        long foreign = jdbc.queryForObject("SELECT MIN(id) FROM business_event WHERE merchant_id=?", Long.class, merchantB);
        mvc.perform(get("/admin/audit-events/" + foreign).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());
        var response = mvc.perform(get("/admin/audit-events").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk()).andReturn();
        for (var item : json.readTree(response.getResponse().getContentAsString()).path("data").path("items")) {
            assertEquals(Long.toString(adminA), item.path("adminId").asText());
        }
        mvc.perform(get("/admin/audit-events/9223372036854775808").header("Authorization", bearer(tokenA)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit-events").param("targetType", "STORE").header("Authorization", bearer(tokenA)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit-events").param("from", "2026-01-01T00:00:00Z").param("to", "2026-03-01T00:00:00Z")
                .header("Authorization", bearer(tokenA))).andExpect(status().isBadRequest());
    }

    @Test
    void mybatisFiltersReadsUpdatesJoinsAndExplicitInsertOwners() throws Exception {
        long own = insertStore(merchantA);
        long other = insertStore(merchantB);
        var identity = identities.load(Identity.Type.ADMIN, adminA);
        as(identity, () -> {
            assertEquals(1, probe.selectList(null).size());
            assertNull(probe.selectById(other));
            assertEquals(0, probe.rename(other, "wrong-tenant"));
            assertEquals(1, probe.rename(own, "own-store"));
            assertEquals(1, probe.joinStores().size());
            assertThrows(Exception.class, () -> probe.insertNamed(merchantB, "wrong"));
            assertEquals(1, probe.insertNamed(merchantA, "allowed"));
            assertEquals(1, probe.insertWithImplicitMerchant());
            assertThrows(Exception.class, () -> probe.insertTwoMerchants(merchantA, merchantB));
            assertThrows(Exception.class, probe::globalCustomers);
            assertThrows(Exception.class, () -> probe.move(own, merchantB));
            assertNull(merchants.selectById(merchantB));
            return null;
        });
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertThrows(Exception.class, () -> probe.selectList(null));
        assertEquals(1, count("SELECT COUNT(*) FROM store WHERE merchant_id=?", merchantB));
    }

    @Test
    void concurrentDuplicateRequestsProduceOneBusinessWriteAndOneAudit() throws Exception {
        var identity = identities.load(Identity.Type.ADMIN, adminA);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<IdempotencyService.Outcome<Map>>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                start.await();
                return as(identity, () -> idempotency.execute("concurrentRename", "PATCH", "/api/v1/admin/merchant",
                        "concurrent-key", Map.of("name", "Once"), Map.class, () -> {
                            merchants.rename(merchantA, "Once");
                            audit.append(merchantA, "CONCURRENT_TEST", "MERCHANT", merchantA, "test", Map.of("summary", "once"));
                            return new IdempotencyService.Outcome<>(200, Map.of("name", "Once"));
                        }));
            }));
            start.countDown();
            for (var future : futures) assertEquals("Once", future.get(15, java.util.concurrent.TimeUnit.SECONDS).data().get("name"));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
        assertEquals(1, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='CONCURRENT_TEST'", merchantA));
    }

    @Test
    void concurrentAdministratorsWithOneEtagCannotOverwriteEachOther() throws Exception {
        insertAdmin(merchantA, "otherAdmin", "ADMIN", "[]");
        String otherToken = login("otherAdmin");
        String etag = merchantEtag(tokenA);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(rename(tokenA, "etag-first-key", etag, "First", "test")).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(rename(otherToken, "etag-second-key", etag, "Second", "test")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(List.of(200, 412), java.util.stream.Stream.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='MERCHANT_NAME_CHANGED'", merchantA));
        assertEquals(1, count("SELECT COUNT(*) FROM api_request WHERE principal_id IN (SELECT id FROM admin_user WHERE merchant_id=?) AND principal_type='ADMIN'", merchantA));
    }

    @Test
    void businessFailureRollsBackDomainAuditAndClaimAndTheSameKeyCanRetry() throws Exception {
        var identity = identities.load(Identity.Type.ADMIN, adminA);
        as(identity, () -> {
            assertThrows(IllegalStateException.class, () -> idempotency.execute("rollbackTest", "PATCH", "/api/v1/admin/merchant",
                    "rollback-key", Map.of("name", "Rollback"), Map.class, () -> {
                        merchants.rename(merchantA, "Must rollback");
                        audit.append(merchantA, "ROLLBACK_TEST", "MERCHANT", merchantA, "test", Map.of("summary", "must rollback"));
                        throw new IllegalStateException("forced failure");
                    }));
            assertEquals(0, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
            assertEquals(0, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='ROLLBACK_TEST'", merchantA));
            assertEquals(prefix + "A", jdbc.queryForObject("SELECT name FROM merchant WHERE id=?", String.class, merchantA));
            idempotency.execute("rollbackTest", "PATCH", "/api/v1/admin/merchant", "rollback-key", Map.of("name", "Rollback"), Map.class,
                    () -> new IdempotencyService.Outcome<>(200, Map.of("accepted", true)));
            return null;
        });
        assertEquals(1, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
    }

    @Test
    void changedBodiesPathsAndPrincipalsHaveTheSpecifiedIdempotencyScopes() throws Exception {
        var identityA = identities.load(Identity.Type.ADMIN, adminA);
        var identityB = identities.load(Identity.Type.ADMIN, adminB);
        as(identityA, () -> {
            var firstBody = new java.util.LinkedHashMap<String, Object>();
            firstBody.put("a", 1); firstBody.put("b", 2);
            idempotency.execute("scopeTest", "POST", "/api/v1/admin/test/1", "shared-key", firstBody, Map.class,
                    () -> new IdempotencyService.Outcome<>(201, Map.of("id", "1")));
            var replay = idempotency.execute("scopeTest", "POST", "/api/v1/admin/test/1", "shared-key", Map.of("b", 2, "a", 1), Map.class,
                    () -> { throw new AssertionError("Canonical JSON replay must not rerun the callback"); });
            assertEquals(201, replay.status());
            var conflict = assertThrows(BusinessException.class, () -> idempotency.execute("scopeTest", "POST", "/api/v1/admin/test/1",
                    "shared-key", Map.of("a", 3), Map.class, () -> null));
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code());
            idempotency.execute("scopeTest", "POST", "/api/v1/admin/test/2", "shared-key", Map.of(), Map.class,
                    () -> new IdempotencyService.Outcome<>(200, Map.of("id", "2")));
            return null;
        });
        as(identityB, () -> idempotency.execute("scopeTest", "POST", "/api/v1/admin/test/1", "shared-key", Map.of(), Map.class,
                () -> new IdempotencyService.Outcome<>(200, Map.of("id", "B"))));
        assertEquals(2, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
        assertEquals(1, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminB));
    }

    @Test
    void revokedIdentitiesCannotReplayAndSecretResultsRollbackInsteadOfPersisting() throws Exception {
        var identity = identities.load(Identity.Type.ADMIN, adminA);
        as(identity, () -> {
            assertThrows(IllegalArgumentException.class, () -> idempotency.execute("secretResult", "POST", "/api/v1/admin/test",
                    "secret-key", Map.of(), Map.class, () -> {
                        merchants.rename(merchantA, "must rollback");
                        return new IdempotencyService.Outcome<>(200, Map.of("accessToken", "never-store-this"));
                    }));
            assertEquals(0, count("SELECT COUNT(*) FROM api_request WHERE principal_id=?", adminA));
            idempotency.execute("revokedReplay", "POST", "/api/v1/admin/test", "revoked-key", Map.of(), Map.class,
                    () -> new IdempotencyService.Outcome<>(200, Map.of("accepted", true)));
            jdbc.update("UPDATE admin_user SET token_version=token_version+1 WHERE id=?", adminA);
            assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(BusinessException.class,
                    () -> idempotency.execute("revokedReplay", "POST", "/api/v1/admin/test", "revoked-key", Map.of(), Map.class, () -> null)).code());
            return null;
        });
    }

    @Test
    void auditAppendRequiresABusinessTransactionAndDatabaseRoleForbidsMutation() throws Exception {
        as(identities.load(Identity.Type.ADMIN, adminA), () -> {
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> audit.append(merchantA, "NO_TRANSACTION", "MERCHANT", merchantA, "test", Map.of()));
            new TransactionTemplate(manager).executeWithoutResult(tx -> audit.append(merchantA, "REDACTED_TEST", "MERCHANT", merchantA,
                    "test", Map.of("summary", "safe", "password", "raw-secret")));
            return null;
        });
        String detail = jdbc.queryForObject("SELECT detail FROM business_event WHERE merchant_id=? AND event_type='REDACTED_TEST'", String.class, merchantA);
        assertFalse(detail.contains("raw-secret"));
        assertTrue(detail.contains("[REDACTED]"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE business_event SET reason='changed' WHERE merchant_id=?", merchantA));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM business_event WHERE merchant_id=?", merchantA));
    }

    @Test
    void customerAuditActorComesFromIdentityAndCannotCrossMerchantMemberships() throws Exception {
        customerLogin("CUSTOMER_B");
        long user = jdbc.queryForObject("SELECT id FROM customer_user WHERE wx_app_id='dev:fixture' AND openid='CUSTOMER_B'", Long.class);
        jdbc.update("INSERT INTO merchant_customer(merchant_id,user_id) VALUES(?,?)", merchantA, user);
        as(identities.load(Identity.Type.CUSTOMER, user), () -> {
            new TransactionTemplate(manager).executeWithoutResult(tx -> audit.append(merchantA, "CUSTOMER_AUDIT_TEST", "MERCHANT", merchantA,
                    null, Map.of("summary", "customer action")));
            var failure = assertThrows(BusinessException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx ->
                    audit.append(merchantB, "CUSTOMER_AUDIT_TEST", "MERCHANT", merchantB, null, Map.of())));
            assertEquals(ErrorCode.NOT_FOUND, failure.code());
            return null;
        });
        var event = jdbc.queryForMap("SELECT actor_type,user_id,admin_id FROM business_event WHERE merchant_id=? AND event_type='CUSTOMER_AUDIT_TEST'", merchantA);
        assertEquals("CUSTOMER", event.get("actor_type"));
        assertEquals(user, ((Number) event.get("user_id")).longValue());
        assertNull(event.get("admin_id"));
        assertEquals(0, count("SELECT COUNT(*) FROM business_event WHERE merchant_id=? AND event_type='CUSTOMER_AUDIT_TEST'", merchantB));
    }

    @Test
    void redisRateLimitsAreAtomicAndDoNotPersistRawIdentifiers() {
        String identifier = prefix + "-rate-test";
        for (int i = 0; i < 3; i++) limiter.check("test", identifier, 3, 60);
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.check("test", identifier, 3, 60)).code());
    }

    private long insertMerchant(String name) {
        jdbc.update("INSERT INTO merchant(name) VALUES(?)", name);
        return jdbc.queryForObject("SELECT id FROM merchant WHERE name=?", Long.class, name);
    }

    private long insertAdmin(long merchant, String suffix, String role, String permissions) {
        jdbc.update("INSERT INTO admin_user(merchant_id,username,password_hash,role,permissions) VALUES(?,?,?,?,?)",
                merchant, prefix + suffix, passwordHash, role, permissions);
        return jdbc.queryForObject("SELECT id FROM admin_user WHERE username=?", Long.class, prefix + suffix);
    }

    private long insertStore(long merchant) {
        jdbc.update("INSERT INTO store(merchant_id,name,address,images,open_time,close_time,capacity) VALUES(?,'fixture','test',JSON_ARRAY(),'08:00:00','22:00:00',10)", merchant);
        return jdbc.queryForObject("SELECT MAX(id) FROM store WHERE merchant_id=?", Long.class, merchant);
    }

    private String login(String suffix) throws Exception {
        var result = mvc.perform(post("/admin/auth/login").with(r -> { r.setRemoteAddr(prefix); return r; })
                .contentType("application/json").content(json.writeValueAsString(Map.of("username", prefix + suffix, "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private String customerLogin(String fixture) throws Exception {
        var result = mvc.perform(post("/dev/auth/customer-login").header("X-Dev-Key", FoundationTestProperties.DEV_KEY)
                .contentType("application/json").content(json.writeValueAsString(Map.of("fixtureKey", fixture))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private String merchantEtag(String token) throws Exception {
        return mvc.perform(get("/admin/merchant").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private MockHttpServletRequestBuilder rename(String token, String key, String etag, String name, String reason) throws Exception {
        var request = patch("/admin/merchant").header("Authorization", bearer(token)).contentType("application/json")
                .content(json.writeValueAsString(Map.of("name", name, "reason", reason)));
        if (key != null) request.header("Idempotency-Key", key);
        if (etag != null) request.header("If-Match", etag);
        return request;
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static String bearer(String token) { return "Bearer " + token; }

    private <T> T as(Identity identity, Callable<T> work) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.authorities()));
        SecurityContextHolder.setContext(context);
        try { return work.call(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    interface ProbeMapper extends BaseMapper<Store> {
        @Update("UPDATE store SET name=#{name} WHERE id=#{id}") int rename(@Param("id") long id, @Param("name") String name);
        @Update("UPDATE store SET merchant_id=#{merchant} WHERE id=#{id}") int move(@Param("id") long id, @Param("merchant") long merchant);
        @Select("SELECT s.id FROM store s JOIN merchant m ON m.id=s.merchant_id") List<Long> joinStores();
        @Insert("INSERT INTO store(merchant_id,name,address,images,open_time,close_time,capacity) "
                + "VALUES(#{merchantId},#{name},'test',JSON_ARRAY(),'08:00:00','22:00:00',10)")
        int insertNamed(@Param("merchantId") long merchantId, @Param("name") String name);
        @Insert("INSERT INTO store(name,address,images,open_time,close_time,capacity) VALUES('implicit','test',JSON_ARRAY(),'08:00:00','22:00:00',10)")
        int insertWithImplicitMerchant();
        @Insert("INSERT INTO store(merchant_id,name,address,images,open_time,close_time,capacity) "
                + "VALUES(#{first},'first','test',JSON_ARRAY(),'08:00:00','22:00:00',10),(#{second},'second','test',JSON_ARRAY(),'08:00:00','22:00:00',10)")
        int insertTwoMerchants(@Param("first") long first, @Param("second") long second);
        @Select("SELECT id FROM customer_user") List<Long> globalCustomers();
    }
}
