package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket signing/verification: expiry, tamper resistance, and track/quality binding. */
class QishuiStreamTicketTests {

    private static final byte[] KEY = "unit-test-signing-key".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripPreservesTrackAndQuality() {
        QishuiStreamTicket t = new QishuiStreamTicket(KEY);
        String ticket = t.issue("7238529778894407741", "320k");
        QishuiStreamTicket.Payload p = t.verify(ticket);
        assertEquals("7238529778894407741", p.getTrackId());
        assertEquals("320k", p.getQuality());
        assertTrue(p.getExpiresAt() > System.currentTimeMillis() / 1000L);
    }

    @Test
    void rejectsExpiredTicket() {
        QishuiStreamTicket t = new QishuiStreamTicket(KEY);
        long past = System.currentTimeMillis() / 1000L - 5000L;
        String ticket = t.issue("id", "128k", past);
        BusinessException e = assertThrows(BusinessException.class, () -> t.verify(ticket));
        assertEquals(400, e.getCode());
    }

    @Test
    void rejectsTamperedPayloadAndSignature() {
        QishuiStreamTicket t = new QishuiStreamTicket(KEY);
        String raw = new String(java.util.Base64.getUrlDecoder().decode(t.issue("id", "128k")),
                StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", -1);
        parts[0] = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("other-track".getBytes(StandardCharsets.UTF_8));
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> t.verify(tamperedPayload));
    }

    @Test
    void differentSigningKeyCannotForge() {
        QishuiStreamTicket a = new QishuiStreamTicket(KEY);
        QishuiStreamTicket b = new QishuiStreamTicket("another-key".getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> b.verify(a.issue("id", "128k")));
    }

    @Test
    void rejectsGarbage() {
        QishuiStreamTicket t = new QishuiStreamTicket(KEY);
        assertThrows(BusinessException.class, () -> t.verify(""));
        assertThrows(BusinessException.class, () -> t.verify("not-a-ticket"));
        assertThrows(BusinessException.class, () -> t.verify(null));
    }
}
