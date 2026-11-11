package com.example.website.module.music.application;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the streaming CENC path is byte-identical to the original whole-buffer decrypt,
 * and that only a faststart header prefix is needed to locate the mdat box.
 *
 * <p>The fixture is a minimal ISO-BMFF file with three samples. A synthetic play-auth blob is
 * built by inverting deriveContentKey()'s scrambling, so no upstream credential is needed.
 */
class QishuiCencStreamTests {

    private static final String HEX_KEY = "00112233445566778899aabbccddeeff";
    private static final int[] SIZES = {100, 200, 150};

    private static byte[] u32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] box(String type, byte[]... parts) {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        ByteBuffer b = ByteBuffer.allocate(len);
        b.putInt(len);
        b.put(type.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] p : parts) b.put(p);
        return b.array();
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    /** Builds a play-auth blob whose derived content key is HEX_KEY. */
    private static String playAuth() {
        byte[] scrambled = new byte[1 + HEX_KEY.length()];
        scrambled[0] = '0'; // decodeBase36 -> skip = 0
        for (int i = 0; i < HEX_KEY.length(); i++) {
            scrambled[1 + i] = (byte) HEX_KEY.charAt(i);
        }
        int innerLen = scrambled.length;
        byte[] inner = new byte[innerLen];
        for (int i = 0; i < innerLen; i++) {
            int buffI = i == 0 ? 0xFA : (i == 1 ? 0x55 : (inner[i - 2] & 0xFF));
            int c = Integer.bitCount(i) + 21;
            int x = ((scrambled[i] & 0xFF) + c) % 255; // invert (x - c) mod 255
            inner[i] = (byte) (x ^ buffI);
        }
        int padding = 4;
        byte[] raw = new byte[innerLen + padding + 1];
        raw[0] = (byte) ((padding + 48) ^ (inner[0] & 0xFF) ^ (inner[1] & 0xFF));
        System.arraycopy(inner, 0, raw, 1, innerLen);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Minimal faststart CENC MP4: ftyp + moov(stbl/stsd/stsz/senc) + mdat. */
    private static byte[] fixture(boolean encaEntry) {
        byte[] stsz = concat(u32(0), u32(0), u32(SIZES.length),
                u32(SIZES[0]), u32(SIZES[1]), u32(SIZES[2]));
        byte[] senc = concat(u32(0), u32(SIZES.length),
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                new byte[]{8, 7, 6, 5, 4, 3, 2, 1},
                new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
        byte[] entry = encaEntry
                ? box("enca", new byte[8], box("frma", "mp4a".getBytes(StandardCharsets.ISO_8859_1)))
                : box("mp4a", new byte[8]);
        byte[] stbl = box("stbl",
                box("stsd", u32(1), entry),
                box("stsz", stsz),
                box("senc", senc));
        byte[] moov = box("moov", box("trak", box("mdia", box("minf", stbl))));
        byte[] ftyp = box("ftyp", new byte[20]);
        int payloadLen = SIZES[0] + SIZES[1] + SIZES[2];
        byte[] payload = new byte[payloadLen];
        for (int i = 0; i < payloadLen; i++) payload[i] = (byte) (i % 251);
        return concat(ftyp, moov, box("mdat", payload));
    }

    private static long[] mdat(byte[] file) {
        long[] m = QishuiCencDecryptor.StreamPlan.locateMdat(file, file.length);
        assertNotNull(m, "fixture must contain a faststart mdat");
        return m;
    }

    @Test
    void derivedKeyIsTheExpectedOne() throws Exception {
        assertArrayEquals(hex(HEX_KEY),
                QishuiCencDecryptor.deriveContentKey(playAuth()));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                    | Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    @Test
    void wholeBufferDecryptIsItsOwnInverseAfterRefactor() throws Exception {
        byte[] base = fixture(false);
        String auth = playAuth();
        byte[] encrypted = QishuiCencDecryptor.decrypt(base, auth);
        assertFalse(java.util.Arrays.equals(base, encrypted), "mdat samples must be transformed");
        byte[] restored = QishuiCencDecryptor.decrypt(encrypted, auth);
        assertArrayEquals(base, restored, "AES-CTR round trip must be byte-identical");
    }

    @Test
    void streamPlanOnAPrefixMatchesWholeBufferDecrypt() throws Exception {
        byte[] base = fixture(false);
        String auth = playAuth();
        byte[] encrypted = QishuiCencDecryptor.decrypt(base, auth);
        byte[] expected = QishuiCencDecryptor.decrypt(encrypted, auth);

        long[] m = mdat(encrypted);
        int prefixEnd = (int) (m[0] + 8) + 10; // only the mdat header + a few payload bytes
        byte[] prefix = java.util.Arrays.copyOf(encrypted, prefixEnd);

        assertNull(QishuiCencDecryptor.StreamPlan.locateMdat(prefix, 40),
                "a truncated moov must not be reported as ready");
        long[] located = QishuiCencDecryptor.StreamPlan.locateMdat(prefix, prefix.length);
        assertNotNull(located, "mdat must be locatable from a header prefix alone");
        assertEquals(m[0], located[0]);
        assertEquals(m[1], located[1]);

        QishuiCencDecryptor.StreamPlan plan = QishuiCencDecryptor.StreamPlan.parse(
                prefix, prefix.length, auth, located[0], located[1], encrypted.length);
        assertEquals((int) (m[0] + 8), plan.mdatPayloadStart());
        assertEquals(encrypted.length, plan.payloadLimit());
        assertArrayEquals(new long[]{100, 200, 150}, plan.sampleSizes());

        byte[] out = encrypted.clone();
        plan.patchHeader(out);
        plan.decryptAll(out);
        assertArrayEquals(expected, out, "streaming decrypt must equal whole-buffer decrypt");
    }

    @Test
    void perSampleDecryptMatchesWholeBufferDecrypt() throws Exception {
        byte[] base = fixture(false);
        String auth = playAuth();
        byte[] encrypted = QishuiCencDecryptor.decrypt(base, auth);
        byte[] expected = QishuiCencDecryptor.decrypt(encrypted, auth);

        long[] m = mdat(encrypted);
        QishuiCencDecryptor.StreamPlan plan = QishuiCencDecryptor.StreamPlan.parse(
                encrypted, encrypted.length, auth, m[0], m[1], encrypted.length);
        byte[] out = encrypted.clone();
        plan.patchHeader(out);
        long[] sizes = plan.sampleSizes();
        int ptr = plan.mdatPayloadStart();
        for (int i = 0; i < sizes.length; i++) {
            int size = (int) sizes[i];
            if (size <= 0 || ptr + size > plan.payloadLimit()) break;
            plan.decryptSample(out, ptr, size, i);
            ptr += size;
        }
        assertArrayEquals(expected, out, "sample-by-sample must equal decryptAll()");
    }

    @Test
    void headerPatchRestoresTheSampleEntryFormat() throws Exception {
        byte[] base = fixture(true);
        String auth = playAuth();
        byte[] decrypted = QishuiCencDecryptor.decrypt(base, auth);
        String before = new String(base, StandardCharsets.ISO_8859_1);
        String after = new String(decrypted, StandardCharsets.ISO_8859_1);
        assertTrue(before.contains("enca"), "fixture must carry the encrypted sample entry");
        assertFalse(after.contains("enca"), "enca must be restored to mp4a");
        assertEquals(base.length, decrypted.length, "patch must not change file length");
    }
}
