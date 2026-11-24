package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.QishuiAccountView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QishuiAuthAutoRefreshTests {
    private final Instant now = Instant.parse("2026-11-10T06:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final QishuiCredentialStore store = mock(QishuiCredentialStore.class);
    private final QishuiAuthClient auth = mock(QishuiAuthClient.class);

    private QishuiAuthAutoRefreshService service(long thresholdSec, boolean enabled) {
        return new QishuiAuthAutoRefreshService(store, auth, clock, thresholdSec, enabled);
    }

    private QishuiCredentialStore.StoredCredential binding(String id, long expiresInSec)
            throws Exception {
        QishuiCredential c = new QishuiCredential();
        c.setUserId("upstream-user");
        c.setDeviceId("device");
        c.setExpiresAt(now.plusSeconds(expiresInSec));
        Constructor<QishuiCredentialStore.StoredCredential> ctor =
                QishuiCredentialStore.StoredCredential.class
                        .getDeclaredConstructor(String.class, QishuiCredential.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, c);
    }

    @Test
    void freshCredentialIsNotRefreshed() throws Exception {
        when(store.load(1L)).thenReturn(Optional.of(binding("b1", 3600)));
        assertFalse(service(1800, true).refreshIfExpiring(1L));
        verify(auth, never()).refresh(any());
        verify(store, never()).updateIfCurrent(anyLong(), anyString(), any());
    }

    @Test
    void expiringCredentialIsRenewedWithCas() throws Exception {
        QishuiCredentialStore.StoredCredential saved = binding("b1", 600);
        QishuiCredential updated = new QishuiCredential();
        when(store.load(1L)).thenReturn(Optional.of(saved));
        when(auth.refresh(saved.getCredential())).thenReturn(updated);
        when(store.updateIfCurrent(1L, "b1", updated)).thenReturn(true);
        assertTrue(service(1800, true).refreshIfExpiring(1L));
        verify(auth, times(1)).refresh(saved.getCredential());
        verify(store).updateIfCurrent(1L, "b1", updated);
    }

    @Test
    void liveRoamingSessionIsRefreshedInPlace() throws Exception {
        // 刷新不再轮换 bindingId，所以直播中的漫游会话不会被 409 打断。
        QishuiCredentialStore.StoredCredential saved = binding("b1", 60);
        QishuiCredential updated = new QishuiCredential();
        when(store.load(1L)).thenReturn(Optional.of(saved));
        when(auth.refresh(saved.getCredential())).thenReturn(updated);
        when(store.updateIfCurrent(1L, "b1", updated)).thenReturn(true);
        assertTrue(service(1800, true).refreshIfExpiring(1L));
        verify(store).updateIfCurrent(1L, "b1", updated);
        // 关键断言：刷新走的是"原地更新"，不会轮换 bindingId 打断会话。
        verify(store, never()).replaceIfCurrent(anyLong(), anyString(), any());
    }

    @Test
    void upstreamFailureIsSwallowedAndNeverPersists() throws Exception {
        when(store.load(1L)).thenReturn(Optional.of(binding("b1", 60)));
        when(auth.refresh(any())).thenThrow(new IllegalStateException("secret-access-code"));
        assertFalse(service(1800, true).refreshIfExpiring(1L));
        verify(store, never()).updateIfCurrent(anyLong(), anyString(), any());
    }

    @Test
    void casConflictIsTreatedAsSkip() throws Exception {
        QishuiCredentialStore.StoredCredential saved = binding("b1", 60);
        when(store.load(1L)).thenReturn(Optional.of(saved));
        when(auth.refresh(any())).thenReturn(new QishuiCredential());
        when(store.updateIfCurrent(eq(1L), eq("b1"), any())).thenReturn(false);
        assertFalse(service(1800, true).refreshIfExpiring(1L));
    }

    @Test
    void nullExpiryIsTreatedAsExpiring() throws Exception {
        QishuiCredentialStore.StoredCredential saved = binding("b1", 3600);
        saved.getCredential().setExpiresAt(null);
        when(store.load(1L)).thenReturn(Optional.of(saved));
        when(auth.refresh(any())).thenReturn(new QishuiCredential());
        when(store.updateIfCurrent(eq(1L), eq("b1"), any())).thenReturn(true);
        assertTrue(service(1800, true).refreshIfExpiring(1L));
    }

    @Test
    void disabledServiceDoesNotTouchStore() {
        assertFalse(service(1800, false).refreshIfExpiring(1L));
        service(1800, false).refreshExpiringAccounts();
        verify(store, never()).load(anyLong());
        verify(store, never()).allUserIds();
    }

    @Test
    void scheduledSweepRefreshesEveryExpiringAccount() throws Exception {
        QishuiCredentialStore.StoredCredential first = binding("b1", 60);
        QishuiCredentialStore.StoredCredential second = binding("b2", 60);
        when(store.allUserIds()).thenReturn(Arrays.asList(1L, 2L));
        when(store.load(1L)).thenReturn(Optional.of(first));
        when(store.load(2L)).thenReturn(Optional.of(second));
        when(auth.refresh(any())).thenReturn(new QishuiCredential());
        when(store.updateIfCurrent(eq(1L), eq("b1"), any())).thenReturn(true);
        when(store.updateIfCurrent(eq(2L), eq("b2"), any())).thenReturn(true);
        service(1800, true).refreshExpiringAccounts();
        verify(auth, times(2)).refresh(any());
        verify(store).updateIfCurrent(eq(1L), eq("b1"), any());
        verify(store).updateIfCurrent(eq(2L), eq("b2"), any());
    }

    @Test
    void accountViewTriggersLazyRefresh() throws Exception {
        when(store.load(1L)).thenReturn(Optional.of(binding("b1", 3600)));
        QishuiAuthAutoRefreshService auto = mock(QishuiAuthAutoRefreshService.class);
        QishuiAccountService accounts = new QishuiAccountService(auth, store, clock, auto);
        QishuiAccountView view = accounts.account(1L);
        verify(auto, times(1)).refreshIfExpiring(1L);
        assertEquals("bound", view.getStatus());
    }

    @Test
    void accountViewStillWorksWithoutAutoRefresh() throws Exception {
        when(store.load(1L)).thenReturn(Optional.of(binding("b1", 3600)));
        // Use the fixed clock (not the real wall clock) so the seeded expiry stays in the
        // future regardless of when the suite runs; autoRefresh stays null = no auto refresh.
        QishuiAccountService accounts = new QishuiAccountService(auth, store, clock);
        assertEquals("bound", accounts.account(1L).getStatus());
    }
}
