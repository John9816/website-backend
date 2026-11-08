package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MusicRequestBudgetTests {
    @Test
    void elapsedDeadlineIsNotExplicitCancellationButInterruptionIs() {
        try (MusicRequestBudget.Scope scope = MusicRequestBudget.start(0, TimeUnit.NANOSECONDS)) {
            assertFalse(MusicRequestBudget.isCancelled());
            assertDoesNotThrow(MusicRequestBudget::ensureNotCancelled);
            assertThrows(MusicBusinessException.class, MusicRequestBudget::ensureRemaining);
            Thread.currentThread().interrupt();
            assertTrue(MusicRequestBudget.isCancelled());
            assertThrows(MusicBusinessException.class, MusicRequestBudget::ensureNotCancelled);
        } finally { Thread.interrupted(); }
        assertFalse(MusicRequestBudget.isCancelled());
    }

    @Test
    void cancellingResolverCancelsRegisteredHttpCallsAndPreventsMoreWork() {
        MusicRequestBudget.CallGroup group = new MusicRequestBudget.CallGroup();
        OkHttpClient client = new OkHttpClient();
        Call call = client.newCall(new Request.Builder().url("https://example.com/audio").build());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try (MusicRequestBudget.Scope scope = MusicRequestBudget.useDeadline(deadline, group)) {
            MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);
            assertFalse(call.isCanceled());
            group.cancel();
            assertTrue(call.isCanceled());
            assertThrows(MusicBusinessException.class, MusicRequestBudget::ensureRemaining);
        }
        assertDoesNotThrow(MusicRequestBudget::ensureRemaining);
    }

    @Test
    void interruptedResolverCannotStartAnotherFallback() {
        try (MusicRequestBudget.Scope scope = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            Thread.currentThread().interrupt();
            assertThrows(MusicBusinessException.class, MusicRequestBudget::ensureRemaining);
        } finally { Thread.interrupted(); }
    }
}
