package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.website.module.music.api.dto.QishuiAccountView;
import com.fasterxml.jackson.databind.*;

import okhttp3.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

class QishuiAuthTests {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void accessFailureIdentifiesStageWithoutReturningUpstreamCredentials() {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
                QishuiClientTests.response(chain,
                        "{\"err_no\":1234,\"err_msg\":\"secret-token-in-upstream-error\",\"data\":{}}"))
                .build();
        QishuiAuthClient client = new QishuiAuthClient(new QishuiHttpClient(http, mapper),
                mapper, "test-client", "test-identity", "test-signature");
        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> client.complete("secret-auth-code", "device", QishuiGuard.create()));
        assertTrue(error.getMessage().contains("获取授权"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void guardSignsExactTicketPathAndTimestampAndCsrIsPem() throws Exception {
        QishuiGuard guard = QishuiGuard.create();
        QishuiCredential c = new QishuiCredential();
        c.setTicket("test-ticket");
        c.setGuardCert("test-cert");
        c.setGuardTsSign("test-ts-sign");
        Map<String, String> h = guard.headers(c, mapper, 123L);
        JsonNode data =
                mapper.readTree(Base64.getDecoder().decode(h.get("bd-ticket-guard-client-data")));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(guard.publicKey());
        verifier.update(
                "ticket=test-ticket&path=/api/luna/v1/platform/me/info/&timestamp=123"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(data.path("req_sign").asText())));
        assertEquals("test-ticket", h.get("access-token"));
        assertEquals("ticket,path,timestamp", data.path("req_content").asText());
        assertTrue(
                new String(Base64.getDecoder().decode(guard.csr()), StandardCharsets.UTF_8)
                        .startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"));
        assertEquals(
                guard.privateScalar(), QishuiGuard.restore(guard.privateScalar()).privateScalar());
        assertArrayEquals(
                guard.publicKey().getEncoded(),
                QishuiGuard.restore(guard.privateScalar()).publicKey().getEncoded());
    }

    @Test
    void qrSessionIsBoundToWebsiteUserAndDoesNotExposeUpstreamToken() {
        QishuiAuthClient client = mock(QishuiAuthClient.class);
        QishuiCredentialStore store = mock(QishuiCredentialStore.class);
        when(client.start())
                .thenReturn(
                        new QishuiAuthClient.LoginStart(
                                "upstream-secret", "qr-content", "https://open.douyin.com/qr"));
        QishuiAccountService service = new QishuiAccountService(client, store, mapper);
        QishuiAccountView view = service.start(1L);
        assertNotNull(view.getSessionId());
        assertEquals("new", view.getStatus());
        assertThrows(BusinessException.class, () -> service.poll(2L, view.getSessionId()));
        verify(client, never()).poll(anyString());
        assertFalse(mapper.valueToTree(view).toString().contains("upstream-secret"));
    }

    @Test
    void replacingSessionAndUnbindingInvalidatesOldPolling() {
        QishuiAuthClient client = mock(QishuiAuthClient.class);
        QishuiCredentialStore store = mock(QishuiCredentialStore.class);
        when(client.start()).thenReturn(new QishuiAuthClient.LoginStart("token", "qr", "url"));
        java.time.Clock clock = mock(java.time.Clock.class);
        when(clock.millis()).thenReturn(10000L, 12000L, 12000L);
        when(clock.instant()).thenReturn(Instant.now());
        QishuiAccountService service = new QishuiAccountService(client, store, clock);
        String first = service.start(1L).getSessionId();
        String second = service.start(1L).getSessionId();
        assertThrows(BusinessException.class, () -> service.poll(1L, first));
        service.delete(1L);
        assertThrows(BusinessException.class, () -> service.poll(1L, second));
        verify(store).delete(1L);
    }

    @Test
    void confirmPersistsOnlyOwnersCredentialAndRepeatedPollIsIdempotent() {
        QishuiAuthClient client = mock(QishuiAuthClient.class);
        QishuiCredentialStore store = mock(QishuiCredentialStore.class);
        when(client.start()).thenReturn(new QishuiAuthClient.LoginStart("token", "qr", "url"));
        when(client.poll("token"))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("status", "confirmed")
                                .put("code", "test-code"));
        QishuiCredential c = new QishuiCredential();
        c.setExpiresAt(Instant.now().plusSeconds(3600));
        when(client.complete(eq("test-code"), anyString(), any(QishuiGuard.class))).thenReturn(c);
        when(store.bindIfCurrent(eq(3L), isNull(), eq(c))).thenReturn(true);
        QishuiAccountService service = new QishuiAccountService(client, store, mapper);
        String id = service.start(3L).getSessionId();
        assertEquals("confirmed", service.poll(3L, id).getStatus());
        assertEquals("confirmed", service.poll(3L, id).getStatus());
        verify(store, times(1)).bindIfCurrent(3L, null, c);
    }

    @Test
    void failedExchangeCannotReplayConsumedAuthorizationCode() {
        QishuiAuthClient client = mock(QishuiAuthClient.class);
        QishuiCredentialStore store = mock(QishuiCredentialStore.class);
        when(client.start()).thenReturn(new QishuiAuthClient.LoginStart("token", "qr", "url"));
        when(client.poll("token"))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("status", "confirmed")
                                .put("auth_code", "code"));
        when(client.complete(eq("code"), anyString(), any())).thenThrow(QishuiHttpClient.failure());
        QishuiAccountService service = new QishuiAccountService(client, store, mapper);
        String id = service.start(1L).getSessionId();
        assertThrows(MusicBusinessException.class, () -> service.poll(1L, id));
        assertEquals("failed", service.poll(1L, id).getStatus());
        verify(client, times(1)).poll("token");
        verify(store, never()).bindIfCurrent(anyLong(), any(), any());
    }

    @Test
    void expiredSessionAndRapidCreationNeverReachUpstream() {
        QishuiAuthClient client = mock(QishuiAuthClient.class);
        QishuiCredentialStore store = mock(QishuiCredentialStore.class);
        when(client.start()).thenReturn(new QishuiAuthClient.LoginStart("token", "qr", "url"));
        java.time.Clock clock = mock(java.time.Clock.class);
        when(clock.millis()).thenReturn(10000L);
        when(clock.instant()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        QishuiAccountService service = new QishuiAccountService(client, store, clock);
        String id = service.start(1L).getSessionId();
        assertThrows(BusinessException.class, () -> service.start(1L));
        when(clock.instant()).thenReturn(Instant.parse("2030-01-01T00:03:01Z"));
        assertThrows(BusinessException.class, () -> service.poll(1L, id));
        verify(client, never()).poll(anyString());
    }

    @Test
    void accessExpirySupportsRelativeSecondsAndEpochSecondsOrMilliseconds() throws Exception {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        assertEquals(
                now.plusSeconds(21600), QishuiAuthClient.expiry(mapper.readTree("21600"), now));
        assertEquals(
                Instant.ofEpochSecond(2000000000L),
                QishuiAuthClient.expiry(mapper.readTree("2000000000"), now));
        assertEquals(
                Instant.ofEpochMilli(2000000000123L),
                QishuiAuthClient.expiry(mapper.readTree("2000000000123"), now));
        assertEquals(now.plusSeconds(21600), QishuiAuthClient.expiry(mapper.missingNode(), now));
    }

    @Test
    void tokenExchangeAndMeUseCorrectHostHeadersAndBody() {
        QishuiGuard guard = QishuiGuard.create();
        List<Request> requests = new ArrayList<>();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    Request r = chain.request();
                                    requests.add(r);
                                    if (r.url().encodedPath().contains("access/code/get"))
                                        return QishuiClientTests.response(
                                                chain,
                                                "{\"err_no\":0,\"data\":{\"access_code\":\"access-test\",\"open_id\":\"open-test\",\"expires_time\":21600}}");
                                    if (r.url().encodedPath().contains("get_access_ticket")) {
                                        assertEquals("POST", r.method());
                                        assertNotNull(r.header("bd-ticket-guard-client-csr"));
                                        assertEquals("1128", r.url().queryParameter("aid"));
                                        return QishuiClientTests.response(
                                                        chain,
                                                        "{\"data\":{\"error_code\":0,\"ticket\":\"ticket-test\",\"expires_time\":4102444800}}")
                                                .newBuilder()
                                                .header(
                                                        "bd-ticket-guard-server-data",
                                                        Base64.getEncoder()
                                                                .encodeToString(
                                                                        "{\"client_cert\":\"cert-test\",\"ts_sign\":\"sign-test\"}"
                                                                                .getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8)))
                                                .build();
                                    }
                                    assertEquals("ticket-test", r.header("access-token"));
                                    assertEquals("POST", r.method());
                                    return QishuiClientTests.response(
                                            chain,
                                            "{\"err_no\":0,\"data\":{\"my_info\":{\"id\":\"q-user\",\"nickname\":\"测试账号\",\"is_vip\":false}}}");
                                })
                        .build();
        QishuiAuthClient client =
                new QishuiAuthClient(
                        new QishuiHttpClient(http, mapper),
                        mapper,
                        "test-client",
                        "test-identity",
                        "test-signature");
        QishuiCredential c = client.complete("code", "device", guard);
        assertTrue(c.getExpiresAt().isAfter(Instant.now().plusSeconds(21590)));
        assertTrue(c.getExpiresAt().isBefore(Instant.now().plusSeconds(21610)));
        assertEquals("q-user", c.getUserId());
        assertEquals("ticket-test", c.getTicket());
        assertEquals(3, requests.size());
    }

    @Test
    void refreshUsesPreviousDeviceKeyAndOpenIdThenSignsNewTicket() throws Exception {
        QishuiGuard guard = QishuiGuard.create();
        QishuiCredential current = new QishuiCredential();
        current.setDeviceId("original-device");
        current.setPrivateScalar(guard.privateScalar());
        current.setOpenId("original-open-id");
        current.setAccessCode("old-access-code");
        current.setTicket("old-ticket");
        List<Request> requests = new ArrayList<>();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    Request r = chain.request();
                                    requests.add(r);
                                    switch (requests.size()) {
                                        case 1:
                                            assertEquals("open.qishui.com", r.url().host());
                                            assertEquals(
                                                    "/api/luna/v1/platform/access/code/refresh/",
                                                    r.url().encodedPath());
                                            assertEquals("GET", r.method());
                                            assertEquals(
                                                    "old-access-code",
                                                    r.url().queryParameter("access_code"));
                                            assertEquals(
                                                    "original-device",
                                                    r.url().queryParameter("device_id"));
                                            return QishuiClientTests.response(
                                                    chain,
                                                    "{\"err_no\":0,\"data\":{\"access_code\":\"new-access-code\",\"expires_time\":2000000000}}");
                                        case 2:
                                            assertEquals("open-client.douyin.com", r.url().host());
                                            assertEquals(
                                                    "/passport/open/get_access_ticket/",
                                                    r.url().encodedPath());
                                            assertEquals("POST", r.method());
                                            assertEquals(
                                                    "access_code=new-access-code&open_id=original-open-id",
                                                    QishuiClientTests.body(r));
                                            assertNotNull(r.header("bd-ticket-guard-client-csr"));
                                            return QishuiClientTests.response(
                                                            chain,
                                                            "{\"data\":{\"error_code\":0,\"ticket\":\"new-ticket\"}}")
                                                    .newBuilder()
                                                    .header(
                                                            "bd-ticket-guard-server-data",
                                                            Base64.getEncoder()
                                                                    .encodeToString(
                                                                            "{\"client_cert\":\"new-cert\",\"ts_sign\":\"new-sign\"}"
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8)))
                                                    .build();
                                        default:
                                            assertEquals(3, requests.size());
                                            assertEquals("open.douyin.com", r.url().host());
                                            assertEquals(
                                                    "/api/luna/v1/platform/me/info/",
                                                    r.url().encodedPath());
                                            assertEquals("POST", r.method());
                                            assertEquals(
                                                    "original-device",
                                                    r.url().queryParameter("device_id"));
                                            assertEquals("new-ticket", r.header("access-token"));
                                            assertEquals(
                                                    "new-cert",
                                                    r.header("bd-ticket-guard-client-cert"));
                                            assertEquals(
                                                    "{\"common_params\":{}}",
                                                    QishuiClientTests.body(r));
                                            return QishuiClientTests.response(
                                                    chain,
                                                    "{\"err_no\":0,\"data\":{\"my_info\":{\"id\":\"test-user\",\"nickname\":\"Refreshed\",\"is_vip\":true}}}");
                                    }
                                })
                        .build();
        QishuiAuthClient client =
                new QishuiAuthClient(
                        new QishuiHttpClient(http, mapper),
                        mapper,
                        "test-client",
                        "test-identity",
                        "test-signature");
        QishuiCredential refreshed = client.refresh(current);
        assertEquals(3, requests.size());
        assertEquals(current.getPrivateScalar(), refreshed.getPrivateScalar());
        assertEquals(current.getOpenId(), refreshed.getOpenId());
        assertEquals("new-access-code", refreshed.getAccessCode());
        assertEquals(Instant.ofEpochSecond(2000000000L), refreshed.getExpiresAt());
        assertEquals("Refreshed", refreshed.getProfile().getNickname());
        assertEquals("old-ticket", current.getTicket());
        JsonNode signed =
                mapper.readTree(
                        Base64.getDecoder()
                                .decode(requests.get(2).header("bd-ticket-guard-client-data")));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(guard.publicKey());
        verifier.update(
                ("ticket=new-ticket&path=/api/luna/v1/platform/me/info/&timestamp="
                                + signed.path("timestamp").asLong())
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signed.path("req_sign").asText())));
        assertEquals("new-sign", signed.path("ts_sign").asText());
    }
}
