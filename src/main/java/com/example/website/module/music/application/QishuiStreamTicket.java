package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Short-lived, signed tickets for the Qishui audio stream endpoint.
 *
 * <p>Browser {@code <audio>} elements cannot send an Authorization header, so the stream URL
 * must be usable without one. A ticket is an HMAC-signed tuple of (trackId, quality, expiry,
 * nonce): it cannot be forged, expires quickly, and is bound to one track and quality — so it
 * cannot be turned into an open proxy for arbitrary upstream URLs. This raises the cost of abuse
 * without banning legitimate playback.
 *
 * <p>The signing key is derived from the application's JWT secret, so no extra credential needs
 * to be provisioned on the server. With a strong (non-default) JWT secret the key is stable
 * across restarts and instances, so in-flight tickets survive a rolling deploy. If the site is
 * still running with the published default secret, a process-random salt is kept as a safety
 * net so tickets still cannot be forged offline. An explicit {@code app.music.qishui.ticket-salt}
 * can be set to pin the key for multi-instance deployments without weakening this fallback.
 */
@Component
public class QishuiStreamTicket {

    private static final long TTL_SECONDS = 1200L; // matches upstream playUrl lifetime
    private static final String DEFAULT_JWT_SECRET =
            "Y2hhbmdlLXRoaXMtdG8tYS1zdHJvbmctc2VjcmV0LWtleS1hdC1sZWFzdC0zMi1ieXRlcw==";
    private static final byte[] DOMAIN = "|qishui-audio-stream-v1".getBytes(StandardCharsets.UTF_8);
    /** Fallback for a weak/default JWT secret: tickets then cannot be forged offline. */
    private static final byte[] PROCESS_SALT = new byte[32];
    static {
        new java.security.SecureRandom().nextBytes(PROCESS_SALT);
    }

    private final byte[] key;

    @Autowired
    public QishuiStreamTicket(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.music.qishui.ticket-salt:}") String ticketSalt) {
        String secret = (jwtSecret == null || jwtSecret.isEmpty()) ? "" : jwtSecret;
        boolean weakSecret = secret.isEmpty() || DEFAULT_JWT_SECRET.equals(secret);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(secret.getBytes(StandardCharsets.UTF_8));
            sha.update(DOMAIN);
            if (ticketSalt != null && !ticketSalt.isEmpty()) {
                sha.update(ticketSalt.getBytes(StandardCharsets.UTF_8));
            } else if (weakSecret) {
                sha.update(PROCESS_SALT);
            }
            this.key = sha.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Visible for tests: treat the argument as the JWT secret with no explicit ticket salt. */
    QishuiStreamTicket(String jwtSecret) {
        this(jwtSecret, "");
    }

    /** Visible for tests. */
    QishuiStreamTicket(byte[] rawKey) {
        this.key = rawKey.clone();
    }

    public String issue(String trackId, String quality) {
        return issue(trackId, quality, System.currentTimeMillis() / 1000L);
    }

    String issue(String trackId, String quality, long nowSeconds) {
        long exp = nowSeconds + TTL_SECONDS;
        String nonce = Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36);
        String payload = enc(trackId) + "|" + enc(quality) + "|" + exp + "|" + nonce;
        String sig = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** @return the parsed (trackId, quality) or throws if the ticket is invalid/expired. */
    public Payload verify(String ticket) {
        if (ticket == null || ticket.isEmpty() || ticket.length() > 2048) {
            throw invalid();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(ticket), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 5) throw invalid();
        String expected = sign(parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[3]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[4].getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (exp < System.currentTimeMillis() / 1000L) throw invalid();
        String id = dec(parts[0]);
        String quality = dec(parts[1]);
        if (id.isEmpty() || quality.isEmpty()) throw invalid();
        return new Payload(id, quality, exp);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] shortSig = new byte[16];
            System.arraycopy(full, 0, shortSig, 0, 16);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(shortSig);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static BusinessException invalid() {
        // 400 (not 502): a bad ticket is a client error, not an upstream failure.
        return new BusinessException(400, "invalid or expired audio stream ticket");
    }

    public static final class Payload {
        private final String trackId;
        private final String quality;
        private final long expiresAt;

        Payload(String trackId, String quality, long expiresAt) {
            this.trackId = trackId;
            this.quality = quality;
            this.expiresAt = expiresAt;
        }

        public String getTrackId() {
            return trackId;
        }

        public String getQuality() {
            return quality;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }
}
