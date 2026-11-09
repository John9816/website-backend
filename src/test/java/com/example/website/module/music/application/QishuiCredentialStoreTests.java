package com.example.website.module.music.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.domain.UserQishuiAccount;
import com.example.website.module.music.infrastructure.persistence.UserQishuiAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.persistence.EntityManager;

@DataJpaTest(showSql = false, properties = "app.music.qishui.encryption-key=")
@Import({QishuiCredentialStore.class, QishuiCredentialStoreTests.JsonConfig.class})
class QishuiCredentialStoreTests {

    // Deliberately fabricated keys and credentials; never copied from an account.
    private static final String TEST_KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_TEST_KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    @Autowired private UserQishuiAccountRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper mapper;
    @Autowired private QishuiCredentialStore unconfiguredStore;
    private QishuiCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new QishuiCredentialStore(repository, mapper, TEST_KEY);
    }

    @Test
    void staleRefreshCannotOverwriteRebindingOrResurrectDeletedAccount() {
        String oldBinding = store.save(101L, credential("old")).getBindingId();
        assertTrue(store.replaceIfCurrent(101L, oldBinding, credential("fresh")));
        assertFalse(store.replaceIfCurrent(101L, oldBinding, credential("stale")));
        assertEquals("upstream-fresh", store.load(101L).get().getCredential().getUserId());
        String binding = store.load(101L).get().getBindingId();
        store.delete(101L);
        assertFalse(store.replaceIfCurrent(101L, binding, credential("stale")));
        assertFalse(store.load(101L).isPresent());
    }

    @Test
    void firstBindingDoesNotOverwriteAnotherCompletedLogin() {
        assertTrue(store.bindIfCurrent(101L, null, credential("first")));
        assertFalse(store.bindIfCurrent(101L, null, credential("second")));
        assertEquals("upstream-first", store.load(101L).get().getCredential().getUserId());
    }

    @Test
    void generationCheckReadsOnlyTheBindingAndObservesReplacementAndDeletion() {
        String binding = store.save(101L,credential("first")).getBindingId();
        UserQishuiAccount row = repository.findById(101L).orElseThrow(AssertionError::new);
        // The lightweight generation query must not load/decrypt the credential payload.
        row.setEncryptedCredential("not-a-credential"); repository.saveAndFlush(row); entityManager.clear();
        assertTrue(store.isCurrent(101L,binding));
        assertFalse(store.isCurrent(102L,binding));
        assertTrue(store.replaceIfCurrent(101L,binding,credential("new")));
        assertFalse(store.isCurrent(101L,binding));
        String next = store.load(101L).get().getBindingId();
        assertTrue(store.isCurrent(101L,next));
        store.delete(101L); repository.flush();
        assertFalse(store.isCurrent(101L,next));
    }

    @Test
    void persistsOnlyCiphertextAndRestoresTheCompleteCredential() {
        QishuiCredential input = credential("alice");
        QishuiCredentialStore.StoredCredential saved = store.save(101L, input);
        entityManager.flush();
        entityManager.clear();

        UserQishuiAccount row = repository.findById(101L).orElseThrow(AssertionError::new);
        assertEquals(Long.valueOf(101L), row.getUserId());
        assertEquals(saved.getBindingId(), row.getBindingId());
        assertDoesNotThrow(() -> UUID.fromString(row.getBindingId()));
        assertNotNull(row.getVersion());
        assertFalse(row.getEncryptedCredential().contains("privateScalar"));
        for (String secret : secrets(input)) {
            assertFalse(row.getEncryptedCredential().contains(secret));
        }

        QishuiCredentialStore.StoredCredential restored =
                store.load(101L).orElseThrow(AssertionError::new);
        assertEquals(saved.getBindingId(), restored.getBindingId());
        assertEquals(mapper.valueToTree(input), mapper.valueToTree(restored.getCredential()));
        assertEquals(
                Instant.parse("2030-01-02T03:04:05Z"), restored.getCredential().getExpiresAt());
        assertEquals("upstream-alice", restored.getCredential().getUserId());
    }

    @Test
    void repeatedSaveUsesFreshIvAndBindingAndAdvancesTheVersion() {
        QishuiCredential input = credential("alice");
        String firstBinding = store.save(101L, input).getBindingId();
        entityManager.flush();
        UserQishuiAccount row = repository.findById(101L).orElseThrow(AssertionError::new);
        String firstEnvelope = row.getEncryptedCredential();
        Long firstVersion = row.getVersion();

        String secondBinding = store.save(101L, input).getBindingId();
        entityManager.flush();
        entityManager.clear();
        row = repository.findById(101L).orElseThrow(AssertionError::new);

        assertNotEquals(firstBinding, secondBinding);
        assertNotEquals(firstEnvelope, row.getEncryptedCredential());
        byte[] firstIv = iv(firstEnvelope);
        byte[] secondIv = iv(row.getEncryptedCredential());
        assertEquals(12, firstIv.length);
        assertEquals(12, secondIv.length);
        assertFalse(Arrays.equals(firstIv, secondIv));
        assertTrue(row.getVersion() > firstVersion);
        assertEquals(
                secondBinding, store.load(101L).orElseThrow(AssertionError::new).getBindingId());
    }

    @Test
    void ciphertextCopiedToAnotherWebsiteUserCannotBeDecrypted() {
        store.save(101L, credential("alice"));
        UserQishuiAccount original = repository.findById(101L).orElseThrow(AssertionError::new);
        UserQishuiAccount transplanted = new UserQishuiAccount();
        transplanted.setUserId(202L);
        transplanted.setBindingId(original.getBindingId());
        transplanted.setEncryptedCredential(original.getEncryptedCredential());
        repository.saveAndFlush(transplanted);
        entityManager.clear();

        assertSafeFailure(() -> store.load(202L));
        assertEquals(
                "upstream-alice",
                store.load(101L).orElseThrow(AssertionError::new).getCredential().getUserId());
    }

    @Test
    void changingBindingIdInvalidatesTheCiphertext() {
        store.save(101L, credential("alice"));
        UserQishuiAccount row = repository.findById(101L).orElseThrow(AssertionError::new);
        row.setBindingId(UUID.randomUUID().toString());
        repository.saveAndFlush(row);
        entityManager.clear();

        assertSafeFailure(() -> store.load(101L));
    }

    @Test
    void tamperedCiphertextIsRejectedWithoutExposingTheCryptoException() {
        store.save(101L, credential("alice"));
        UserQishuiAccount row = repository.findById(101L).orElseThrow(AssertionError::new);
        String[] envelope = row.getEncryptedCredential().split("\\.");
        byte[] bytes = Base64.getDecoder().decode(envelope[2]);
        bytes[bytes.length - 1] ^= 1;
        row.setEncryptedCredential(
                envelope[0] + "." + envelope[1] + "." + Base64.getEncoder().encodeToString(bytes));
        repository.saveAndFlush(row);
        entityManager.clear();

        assertSafeFailure(() -> store.load(101L));
    }

    @Test
    void anotherEncryptionKeyCannotReadTheStoredCredential() {
        store.save(101L, credential("alice"));
        QishuiCredentialStore otherKey =
                new QishuiCredentialStore(repository, mapper, OTHER_TEST_KEY);

        assertSafeFailure(() -> otherKey.load(101L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not-base64", "dGVzdA=="})
    void missingOrInvalidKeyDoesNotPreventConstructionButRejectsSaveAndLoad(String key) {
        QishuiCredentialStore unavailable =
                assertDoesNotThrow(() -> new QishuiCredentialStore(repository, mapper, key));

        assertSafeFailure(() -> unavailable.save(101L, credential("alice")));
        assertSafeFailure(() -> unavailable.load(101L));
        assertFalse(repository.existsById(101L));
    }

    @Test
    void springCanCreateTheStoreWithoutAnEncryptionKey() {
        assertNotNull(unconfiguredStore);
        assertSafeFailure(() -> unconfiguredStore.load(101L));
    }

    @Test
    void loadingAnotherUserDoesNotReturnTheExistingAccount() {
        store.save(101L, credential("alice"));

        assertFalse(store.load(202L).isPresent());
        assertEquals(
                "upstream-alice",
                store.load(101L).orElseThrow(AssertionError::new).getCredential().getUserId());
    }

    @Test
    void deleteRemovesOnlyItsOwnerAndWorksWithoutTheKey() {
        store.save(101L, credential("alice"));
        store.save(202L, credential("bob"));

        unconfiguredStore.delete(101L);
        unconfiguredStore.delete(101L);
        entityManager.flush();
        entityManager.clear();

        assertFalse(store.load(101L).isPresent());
        assertEquals(
                "upstream-bob",
                store.load(202L).orElseThrow(AssertionError::new).getCredential().getUserId());
    }

    @Test
    void staleAccountCannotOverwriteANewerBinding() {
        store.save(101L, credential("alice"));
        UserQishuiAccount stale = repository.findById(101L).orElseThrow(AssertionError::new);
        entityManager.detach(stale);
        store.save(101L, credential("replacement"));
        entityManager.flush();
        entityManager.clear();
        stale.setBindingId(UUID.randomUUID().toString());

        assertThrows(
                ObjectOptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(stale));
    }

    @Test
    void credentialAndStoredResultDoNotRenderSecretsInToString() {
        QishuiCredential input = credential("alice");
        QishuiCredentialStore.StoredCredential saved = store.save(101L, input);

        for (String secret : secrets(input)) {
            assertFalse(input.toString().contains(secret));
            assertFalse(saved.toString().contains(secret));
        }
    }

    private static byte[] iv(String envelope) {
        String[] parts = envelope.split("\\.");
        assertEquals(3, parts.length);
        assertEquals("v1", parts[0]);
        return Base64.getDecoder().decode(parts[1]);
    }

    private static void assertSafeFailure(org.junit.jupiter.api.function.Executable operation) {
        MusicBusinessException error = assertThrows(MusicBusinessException.class, operation);
        assertEquals(MusicErrorCode.MISSING_UPSTREAM_TOKEN, error.getErrorCode());
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().trim().isEmpty());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains(TEST_KEY));
        assertFalse(error.getMessage().contains("fake-secret"));
    }

    private static String[] secrets(QishuiCredential credential) {
        return new String[] {
            credential.getPrivateScalar(),
            credential.getDeviceId(),
            credential.getTicket(),
            credential.getGuardTsSign(),
            credential.getGuardCert(),
            credential.getAccessCode(),
            credential.getOpenId(),
            credential.getUserId()
        };
    }

    private static QishuiCredential credential(String label) {
        QishuiCredential credential = new QishuiCredential();
        credential.setPrivateScalar("fake-secret-private-scalar-" + label);
        credential.setDeviceId("fake-secret-device-" + label);
        credential.setTicket("fake-secret-ticket-" + label);
        credential.setGuardTsSign("fake-secret-guard-sign-" + label);
        credential.setGuardCert("fake-secret-guard-cert-" + label);
        credential.setAccessCode("fake-secret-access-code-" + label);
        credential.setOpenId("fake-secret-open-id-" + label);
        credential.setUserId("upstream-" + label);
        credential.setExpiresAt(Instant.parse("2030-01-02T03:04:05Z"));
        QishuiCredential.Profile profile = new QishuiCredential.Profile();
        profile.setNickname("Test " + label);
        profile.setAvatarUrl("https://example.test/avatar.png");
        profile.setIsVip(Boolean.TRUE);
        credential.setProfile(profile);
        return credential;
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }
}
