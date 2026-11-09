package com.example.website.module.music.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Disk cache bounds + quality-key normalisation, exercised without a Spring context. */
class QishuiDiskCacheTests {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Path> binFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .collect(Collectors.toList());
        }
    }

    @Test
    void roundTripAndKeyIsolation(@TempDir Path dir) {
        QishuiDiskCache c = new QishuiDiskCache(dir.toString(), 1024 * 1024, 600);
        assertTrue(c.isEnabled());
        byte[] a = bytes("first-track-plaintext");
        byte[] b = bytes("second-track-plaintext");
        c.write("trackA|flac", a);
        assertArrayEquals(a, c.read("trackA|flac"));
        assertNull(c.read("trackB|flac"));
        c.write("trackB|flac", b);
        assertArrayEquals(b, c.read("trackB|flac"));
        assertArrayEquals(a, c.read("trackA|flac"));
    }

    @Test
    void expiredEntryIsDiscarded(@TempDir Path dir) throws IOException {
        QishuiDiskCache c = new QishuiDiskCache(dir.toString(), 1024 * 1024, 60);
        c.write("track|flac", bytes("payload"));
        List<Path> files = binFiles(dir);
        assertEquals(1, files.size());
        Files.setLastModifiedTime(files.get(0),
                FileTime.fromMillis(System.currentTimeMillis() - 120_000));
        assertNull(c.read("track|flac"));
        assertEquals(0, binFiles(dir).size());
    }

    @Test
    void sizeBoundEvictsOldest(@TempDir Path dir) throws IOException {
        QishuiDiskCache c = new QishuiDiskCache(dir.toString(), 25, 600);
        c.write("old|flac", bytes("12345678901234567890"));
        List<Path> first = binFiles(dir);
        assertEquals(1, first.size());
        Files.setLastModifiedTime(first.get(0),
                FileTime.fromMillis(System.currentTimeMillis() - 10_000));
        c.write("new|flac", bytes("abcdefghijklmnopqrst"));
        assertNull(c.read("old|flac"));
        assertArrayEquals(bytes("abcdefghijklmnopqrst"), c.read("new|flac"));
    }

    @Test
    void unusablePathDisablesCacheWithoutBreakingPlayback(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("not-a-dir");
        Files.write(blocker, bytes("x"));
        QishuiDiskCache c = new QishuiDiskCache(blocker.toString(), 1024, 600);
        assertFalse(c.isEnabled());
        assertNull(c.read("any|flac"));
        c.write("any|flac", bytes("payload"));  // must not throw
    }

    @Test
    void flacAndHiResShareOneCacheKey() {
        assertEquals(QishuiStreamService.cacheKey("7491130229642283025", "flac"),
                QishuiStreamService.cacheKey("7491130229642283025", "flac24bit"));
        assertEquals("flac", QishuiStreamService.normalizeQuality("flac24bit"));
        assertNotEquals(QishuiStreamService.cacheKey("t", "flac"),
                QishuiStreamService.cacheKey("t", "320k"));
        assertNotEquals(QishuiStreamService.cacheKey("t1", "flac"),
                QishuiStreamService.cacheKey("t2", "flac"));
        assertEquals("128k", QishuiStreamService.normalizeQuality("128k"));
        assertEquals("", QishuiStreamService.normalizeQuality(null));
    }
}
