package com.example.website.module.music.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the streaming Range path: the controller no longer materialises the
 * whole track, so positional reads and Range parsing must be exact (seek depends on it).
 */
class QishuiStreamRangeTests {

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i % 251);
        return b;
    }

    private static QishuiStreamService.AudioFile diskFile(Path dir, byte[] data) throws Exception {
        Path f = dir.resolve("track.bin");
        Files.write(f, data);
        Constructor<QishuiStreamService.AudioFile> c =
                QishuiStreamService.AudioFile.class.getDeclaredConstructor(
                        Path.class, byte[].class, long.class);
        c.setAccessible(true);
        return c.newInstance(f, null, (long) data.length);
    }

    private static QishuiStreamService.AudioFile memoryFile(byte[] data) throws Exception {
        Constructor<QishuiStreamService.AudioFile> c =
                QishuiStreamService.AudioFile.class.getDeclaredConstructor(
                        Path.class, byte[].class, long.class);
        c.setAccessible(true);
        return c.newInstance(null, data, (long) data.length);
    }

    /** Range.parse is private on the controller; drive it reflectively. */
    private static Object parseRange(String header, long length) throws Exception {
        Class<?> range = Class.forName(
                "com.example.website.module.music.api.controller.QishuiStreamController$Range");
        Method m = range.getDeclaredMethod("parse", String.class, long.class);
        m.setAccessible(true);
        return m.invoke(null, header, length);
    }

    private static long[] bounds(Object r) throws Exception {
        Class<?> c = r.getClass();
        java.lang.reflect.Field startField = c.getDeclaredField("start");
        java.lang.reflect.Field endField = c.getDeclaredField("end");
        java.lang.reflect.Field partialField = c.getDeclaredField("partial");
        startField.setAccessible(true);
        endField.setAccessible(true);
        partialField.setAccessible(true);
        return new long[]{startField.getLong(r), endField.getLong(r),
                partialField.getBoolean(r) ? 1 : 0};
    }

    @Test
    void positionalReadMatchesTheRequestedByteRange(@TempDir Path dir) throws Exception {
        byte[] data = payload(200_000); // > 3 chunks of 64 KB
        QishuiStreamService.AudioFile f = diskFile(dir, data);
        assertEquals(data.length, f.length());
        assertTrue(f.isValid());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        f.writeRange(out, 70_000, 150_000);
        byte[] got = out.toByteArray();
        assertEquals(80_001, got.length);
        for (int i = 0; i < got.length; i++) {
            assertEquals(data[70_000 + i], got[i], "byte at offset " + i);
        }
    }

    @Test
    void inMemoryFallbackReturnsTheSameBytes(@TempDir Path dir) throws Exception {
        byte[] data = payload(9_999);
        QishuiStreamService.AudioFile disk = diskFile(dir, data);
        QishuiStreamService.AudioFile mem = memoryFile(data);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        disk.writeRange(a, 1234, 5678);
        mem.writeRange(b, 1234, 5678);
        assertArrayEquals(a.toByteArray(), b.toByteArray());
    }

    @Test
    void invalidHandleIsDetected(@TempDir Path dir) throws Exception {
        QishuiStreamService.AudioFile f = diskFile(dir, payload(10));
        Files.delete(dir.resolve("track.bin"));
        assertFalse(f.isValid(), "a trimmed cache file must force a reload");
    }

    @Test
    void rangeParsingCoversSeekSuffixAndFullRequests() throws Exception {
        long len = 1_000L;
        assertArrayEquals(new long[]{0, 999, 0}, bounds(parseRange(null, len)));
        assertArrayEquals(new long[]{100, 499, 1}, bounds(parseRange("bytes=100-499", len)));
        assertArrayEquals(new long[]{500, 999, 1}, bounds(parseRange("bytes=500-", len)));
        assertArrayEquals(new long[]{900, 999, 1}, bounds(parseRange("bytes=-100", len)));
        assertArrayEquals(new long[]{0, 999, 1}, bounds(parseRange("bytes=-5000", len)));
        // end beyond EOF is clamped, start beyond EOF is unsatisfiable
        assertArrayEquals(new long[]{700, 999, 1}, bounds(parseRange("bytes=700-99999", len)));
        assertNull(parseRange("bytes=1000-", len));
        assertNull(parseRange("bytes=abc-def", len));
    }

    @Test
    void emptyRangeWritesNothing(@TempDir Path dir) throws Exception {
        QishuiStreamService.AudioFile f = diskFile(dir, payload(10));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        f.writeRange(out, 5, 4);
        assertEquals(0, out.size());
    }

    @Test
    void cacheKeyNormalisationIsUnchanged() {
        assertEquals(QishuiStreamService.cacheKey("7491130229642283025", "flac"),
                QishuiStreamService.cacheKey("7491130229642283025", "flac24bit"));
        assertNotEquals(QishuiStreamService.cacheKey("t", "flac"),
                QishuiStreamService.cacheKey("t", "320k"));
        assertEquals("", QishuiStreamService.normalizeQuality(null));
    }
}
