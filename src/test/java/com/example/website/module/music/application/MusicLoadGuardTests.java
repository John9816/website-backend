package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.module.music.api.dto.MusicSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicLoadGuardTests {
    @Test
    void saturatedSourceDoesNotBlockOtherSourcesAndClosingRestoresCapacity() {
        MusicLoadGuard guard = new MusicLoadGuard(1);
        try (MusicLoadGuard.Lease first = guard.acquire(MusicSource.QQ)) {
            MusicBusinessException busy = assertThrows(MusicBusinessException.class,
                    () -> guard.acquire(MusicSource.QQ));
            assertEquals(503, busy.getErrorCode().getHttpStatus().value());
            try (MusicLoadGuard.Lease other = guard.acquire(MusicSource.KUWO)) {
                assertNotNull(other);
            }
        }
        try (MusicLoadGuard.Lease recovered = guard.acquire(MusicSource.QQ)) {
            assertNotNull(recovered);
            recovered.close();
            recovered.close();
            try (MusicLoadGuard.Lease one = guard.acquire(MusicSource.QQ)) {
                assertThrows(MusicBusinessException.class, () -> guard.acquire(MusicSource.QQ));
            }
        }
    }
}
