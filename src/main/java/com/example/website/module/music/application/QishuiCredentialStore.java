package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.domain.UserQishuiAccount;
import com.example.website.module.music.infrastructure.persistence.UserQishuiAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class QishuiCredentialStore {
    private static final String ENVELOPE_VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final UserQishuiAccountRepository repository;
    private final ObjectMapper mapper;
    private final String encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public QishuiCredentialStore(
            UserQishuiAccountRepository repository,
            ObjectMapper mapper,
            @Value("${app.music.qishui.encryption-key:}") String encryptionKey) {
        this.repository = repository;
        this.mapper = mapper;
        this.encryptionKey = encryptionKey;
    }

    @Transactional
    public StoredCredential save(Long userId, QishuiCredential credential) {
        SecretKeySpec key = encryptionKey();
        Objects.requireNonNull(userId, "Website user ID is required");
        Objects.requireNonNull(credential, "Qishui credential is required");
        String bindingId = UUID.randomUUID().toString();
        String encrypted = encrypt(userId, bindingId, credential, key);

        UserQishuiAccount account = repository.findById(userId).orElseGet(UserQishuiAccount::new);
        account.setUserId(userId);
        account.setBindingId(bindingId);
        account.setEncryptedCredential(encrypted);
        // Keep the existing version so concurrent rebindings cannot silently overwrite each other.
        repository.saveAndFlush(account);
        return new StoredCredential(bindingId, credential);
    }

    @Transactional(readOnly = true)
    public Optional<StoredCredential> load(Long userId) {
        SecretKeySpec key = encryptionKey();
        Objects.requireNonNull(userId, "Website user ID is required");
        return repository
                .findById(userId)
                .map(
                        account ->
                                new StoredCredential(
                                        account.getBindingId(), decrypt(userId, account, key)));
    }

    @Transactional
    public void delete(Long userId) {
        Objects.requireNonNull(userId, "Website user ID is required");
        repository.findById(userId).ifPresent(repository::delete);
    }

    /** Always query the database generation; never cache authorization revocation checks. */
    @Transactional(readOnly = true)
    public boolean isCurrent(Long userId, String bindingId) {
        Objects.requireNonNull(userId, "Website user ID is required");
        Objects.requireNonNull(bindingId, "Binding ID is required");
        return repository.findBindingId(userId).filter(bindingId::equals).isPresent();
    }

    @Transactional
    public boolean bindIfCurrent(
            Long userId, String expectedBindingId, QishuiCredential credential) {
        if (expectedBindingId != null)
            return replaceIfCurrent(userId, expectedBindingId, credential);
        if (repository.existsById(userId)) return false;
        String binding = UUID.randomUUID().toString();
        UserQishuiAccount account = new UserQishuiAccount();
        account.setUserId(userId);
        account.setBindingId(binding);
        account.setEncryptedCredential(encrypt(userId, binding, credential, encryptionKey()));
        // A concurrent first binding fails the primary-key insert, rather than merging it.
        repository.saveAndFlush(account);
        return true;
    }

    public void requireConfigured() {
        encryptionKey();
    }

    /** Only user IDs, so the scheduled auth-refresh sweep never reads credential LOBs. */
    @Transactional(readOnly = true)
    public java.util.List<Long> allUserIds() {
        return repository.findAllUserIds();
    }

    @Transactional
    public boolean replaceIfCurrent(
            Long userId, String expectedBindingId, QishuiCredential credential) {
        String nextBinding = UUID.randomUUID().toString();
        String encrypted = encrypt(userId, nextBinding, credential, encryptionKey());
        return repository.replaceIfCurrent(userId, expectedBindingId, nextBinding, encrypted) == 1;
    }

    private SecretKeySpec encryptionKey() {
        if (encryptionKey == null || encryptionKey.trim().isEmpty()) {
            throw unavailable("Qishui credential encryption is not configured");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey.trim());
        } catch (IllegalArgumentException ignored) {
            throw unavailable("Qishui credential encryption is not configured");
        }
        try {
            if (decoded.length != 32) {
                throw unavailable("Qishui credential encryption is not configured");
            }
            return new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private String encrypt(
            Long userId, String bindingId, QishuiCredential credential, SecretKeySpec key) {
        byte[] plaintext = null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(authenticationData(userId, bindingId));
            plaintext = mapper.writeValueAsBytes(credential);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ENVELOPE_VERSION
                    + "."
                    + Base64.getEncoder().encodeToString(iv)
                    + "."
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ignored) {
            throw unavailable("Qishui account credential could not be saved");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private QishuiCredential decrypt(Long userId, UserQishuiAccount account, SecretKeySpec key) {
        byte[] plaintext = null;
        try {
            if (account.getEncryptedCredential() == null || account.getBindingId() == null) {
                throw new IllegalArgumentException();
            }
            String[] envelope = account.getEncryptedCredential().split("\\.", -1);
            if (envelope.length != 3 || !ENVELOPE_VERSION.equals(envelope[0])) {
                throw new IllegalArgumentException();
            }
            byte[] iv = Base64.getDecoder().decode(envelope[1]);
            byte[] ciphertext = Base64.getDecoder().decode(envelope[2]);
            if (iv.length != IV_BYTES || ciphertext.length < TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(authenticationData(userId, account.getBindingId()));
            plaintext = cipher.doFinal(ciphertext);
            QishuiCredential credential = mapper.readValue(plaintext, QishuiCredential.class);
            if (credential == null) {
                throw new IllegalArgumentException();
            }
            return credential;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ignored) {
            // Do not attach the original exception: JSON errors can include plaintext snippets.
            throw unavailable(
                    "Qishui account credential could not be read; please authorize again");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] authenticationData(Long userId, String bindingId) {
        return ("qishui-credential:"
                        + ENVELOPE_VERSION
                        + "\nuserId="
                        + userId
                        + "\nbindingId="
                        + bindingId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private MusicBusinessException unavailable(String message) {
        return new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN, message);
    }

    public static final class StoredCredential {
        private final String bindingId;
        private final QishuiCredential credential;

        private StoredCredential(String bindingId, QishuiCredential credential) {
            this.bindingId = bindingId;
            this.credential = credential;
        }

        public String getBindingId() {
            return bindingId;
        }

        public QishuiCredential getCredential() {
            return credential;
        }
    }
}
