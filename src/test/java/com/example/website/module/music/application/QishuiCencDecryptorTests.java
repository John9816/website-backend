package com.example.website.module.music.application;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-contained tests for the pure-Java CENC decryptor. Encrypted MP4 fixtures are built
 * programmatically (no copyrighted audio is embedded) by encrypting sample payloads with the
 * same AES-CTR scheme, then decrypting them back through {@link QishuiCencDecryptor}.
 */
class QishuiCencDecryptorTests {

    private static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** Builds a play-auth blob whose derived content key is {@link #KEY}. */
    private static String playAuthFor(byte[] key) {
        String hex = toHex(key);
        // Desired unscrambled core: [base36 '0' => skip=0][32 hex chars]
        byte[] core = new byte[1 + hex.length()];
        core[0] = '0';
        System.arraycopy(hex.getBytes(StandardCharsets.ISO_8859_1), 0, core, 1, hex.length());

        // unscramble() computes core[i] = f(inner[i], buff[i], i) where buff[i] = inner[i-2].
        // Recover inner byte-by-byte by search, so the fixture exactly inverts the real decoder.
        byte[] inner = new byte[core.length];
        for (int i = 0; i < core.length; i++) {
            int buffI = i == 0 ? 0xFA : (i == 1 ? 0x55 : (inner[i - 2] & 0xFF));
            boolean found = false;
            for (int cand = 0; cand < 256; cand++) {
                int v = (cand ^ buffI) - Integer.bitCount(i) - 21;
                while (v < 0) v += 255;
                if ((v & 0xFF) == (core[i] & 0xFF)) {
                    inner[i] = (byte) cand;
                    found = true;
                    break;
                }
            }
            assertTrue(found, "no inverse byte at index " + i);
        }

        // padding = (b0 ^ b1 ^ b2) - 48, target 2 => b0 = 50 ^ inner[0] ^ inner[1]
        int padding = 2;
        byte[] raw = new byte[1 + inner.length + padding];
        raw[0] = (byte) ((padding + 48) ^ (inner[0] & 0xFF) ^ (inner[1] & 0xFF));
        System.arraycopy(inner, 0, raw, 1, inner.length);
        raw[raw.length - 2] = 0x11;
        raw[raw.length - 1] = 0x22;
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    @Test
    void derivesSixteenByteContentKey() throws Exception {
        byte[] key = QishuiCencDecryptor.deriveContentKey(playAuthFor(KEY));
        assertArrayEquals(KEY, key);
    }

    @Test
    void rejectsMalformedPlayAuthAndChangesKeyWhenTampered() throws Exception {
        assertThrows(Exception.class, () -> QishuiCencDecryptor.deriveContentKey(""));
        assertThrows(Exception.class, () -> QishuiCencDecryptor.deriveContentKey("!!!not-base64!!!"));
        // Tampering with a hex digit yields a different (still well-formed) key, so the audio
        // would fail to decrypt — it must never silently reproduce the original key.
        byte[] raw = Base64.getDecoder().decode(playAuthFor(KEY));
        raw[4] ^= 0x01;
        byte[] tamperedKey = QishuiCencDecryptor.deriveContentKey(
                Base64.getEncoder().encodeToString(raw));
        assertFalse(Arrays.equals(KEY, tamperedKey));
    }

    @Test
    void decryptsWholeSampleEncryptedMp4BackToPlaintext() throws Exception {
        byte[] plain = new byte[64];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 7 + 3);
        byte[] iv = iv8();
        byte[] encrypted = ctr(KEY, iv, plain);
        String playAuth = playAuthFor(KEY);

        byte[] input = buildMp4(encrypted, iv, null, true);
        byte[] out = QishuiCencDecryptor.decrypt(input, playAuth);

        assertEquals(input.length, out.length);
        assertArrayEquals(plain, Arrays.copyOfRange(out, mdatPayloadOffset(input),
                mdatPayloadOffset(input) + plain.length));
        assertFalse(contains(out, "enca"));
        assertTrue(contains(out, "mp4a"));
    }

    @Test
    void honorsSubsampleClearAndEncryptedRanges() throws Exception {
        byte[] plain = new byte[64];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) (0xA0 + i);
        byte[] iv = iv8();

        // First 16 bytes clear, remaining 48 encrypted. Per the 'cenc' subsample scheme the
        // keystream is applied to the encrypted bytes only, starting from keystream offset 0.
        byte[] subPlain = Arrays.copyOfRange(plain, 16, 64);
        byte[] subCipher = ctr(KEY, iv, subPlain);
        byte[] encrypted = plain.clone();
        System.arraycopy(subCipher, 0, encrypted, 16, 48);
        int[][] subs = {{16, 48}};

        byte[] input = buildMp4(encrypted, iv, subs, true);
        byte[] out = QishuiCencDecryptor.decrypt(input, playAuthFor(KEY));
        assertArrayEquals(plain, Arrays.copyOfRange(out, mdatPayloadOffset(input),
                mdatPayloadOffset(input) + plain.length));
    }

    @Test
    void rejectsNonMp4Data() {
        assertThrows(Exception.class,
                () -> QishuiCencDecryptor.decrypt(new byte[] {1, 2, 3, 4}, playAuthFor(KEY)));
    }

    // -------------------------------------------------------------- helpers

    private static byte[] iv8() {
        return new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    }

    private static byte[] ctr(byte[] key, byte[] iv8, byte[] input) throws Exception {
        byte[] iv = new byte[16];
        System.arraycopy(iv8, 0, iv, 0, 8);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(input);
    }

    private static int mdatPayloadOffset(byte[] mp4) {
        int i = indexOf(mp4, "mdat");
        return i - 4 + 8;
    }

    private static boolean contains(byte[] data, String s) {
        return indexOf(data, s) >= 0;
    }

    private static int indexOf(byte[] data, String s) {
        byte[] needle = s.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Builds ftyp + moov(trak/mdia/minf/stbl(stsd(enca+frma), stsz, senc), tenc) + mdat.
     * Box bodies are padded with zeros where real files carry unrelated fields.
     */
    private static byte[] buildMp4(byte[] mdatPayload, byte[] iv8, int[][] subs, boolean withEnca) {
        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        // stsd: version/flags + entry count + enca box (with frma child declaring mp4a)
        byte[] stsdBody = new byte[8 + 8 + 28 + 12];
        stsdBody[7] = 1; // entry_count = 1
        int enca = 8;
        putInt(stsdBody, enca, 8 + 28 + 12); // enca box size
        System.arraycopy("enca".getBytes(StandardCharsets.ISO_8859_1), 0, stsdBody, enca + 4, 4);
        int frma = enca + 8 + 28;
        putInt(stsdBody, frma, 12);
        System.arraycopy("frma".getBytes(StandardCharsets.ISO_8859_1), 0, stsdBody, frma + 4, 4);
        System.arraycopy("mp4a".getBytes(StandardCharsets.ISO_8859_1), 0, stsdBody, frma + 8, 4);

        byte[] stsz = box("stsz", concat(new byte[4], ints(0, 1), ints(mdatPayload.length)));
        byte[] senc = senc(iv8, subs);
        byte[] tenc = box("tenc", new byte[] {0, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0});

        byte[] stbl = box("stbl", concat(box("stsd", stsdBody), stsz, senc, tenc));
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", minf);
        byte[] trak = box("trak", mdia);
        writeBox(moov, "moov", trak);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBox(out, "ftyp", "M4A isom".getBytes(StandardCharsets.ISO_8859_1));
        byte[] moovBytes = moov.toByteArray();
        out.write(moovBytes, 0, moovBytes.length);
        writeBox(out, "mdat", mdatPayload);
        return out.toByteArray();
    }

    private static byte[] senc(byte[] iv8, int[][] subs) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] head = new byte[8];
        if (subs != null) head[3] = 0x02; // flags: use-subsample-encryption
        head[7] = 1; // sample_count = 1
        b.write(head, 0, 8);
        b.write(iv8, 0, iv8.length);
        if (subs != null) {
            b.write(0);
            b.write(subs.length);
            for (int[] s : subs) {
                byte[] rec = new byte[6];
                rec[0] = (byte) (s[0] >> 8);
                rec[1] = (byte) s[0];
                rec[2] = (byte) (s[1] >> 24);
                rec[3] = (byte) (s[1] >> 16);
                rec[4] = (byte) (s[1] >> 8);
                rec[5] = (byte) s[1];
                b.write(rec, 0, 6);
            }
        }
        return box("senc", b.toByteArray());
    }

    private static byte[] box(String type, byte[] body) {
        byte[] out = new byte[8 + body.length];
        putInt(out, 0, out.length);
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, out, 4, 4);
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] body) {
        byte[] b = box(type, body);
        out.write(b, 0, b.length);
    }

    private static byte[] ints(int... values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) putInt(out, i * 4, values[i]);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static void putInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }
}
