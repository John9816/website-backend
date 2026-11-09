package com.example.website.module.music.application;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Decrypts Qishui CENC-protected MP4/M4A audio into a plain, browser-playable file.
 *
 * <p>Implementation notes: the container parsing follows ISO/IEC 14496-12 (ISO BMFF) and the
 * sample encryption follows ISO/IEC 23001-7 (Common Encryption, 'cenc' / AES-CTR). Both are
 * published standards. The per-sample IVs and subsample ranges come from the 'senc' box, sample
 * sizes from 'stsz', and the AES content key is derived from the play-auth blob that the upstream
 * parse response supplies. This class only uses the JDK (javax.crypto / java.util) and has no
 * third-party or Spring dependencies, so it can be unit-tested in isolation.
 *
 * <p>The output has the same byte length as the input: only 'mdat' sample payloads are
 * transformed (AES-CTR is a stream cipher) and the 'enca' sample entry is restored to its
 * original format ('mp4a'), so existing offsets and 'moov' boxes stay valid.
 */
public final class QishuiCencDecryptor {

    private QishuiCencDecryptor() {
    }

    /** Thrown when the input is not a well-formed CENC-protected MP4 file. */
    public static final class DecryptException extends GeneralSecurityException {
        public DecryptException(String message) {
            super(message);
        }
    }

    /**
     * @param fileData encrypted MP4 bytes as fetched from the upstream play URL
     * @param playAuth  the spadeA / play-auth blob returned alongside the play URL
     * @return plain MP4 bytes of exactly the same length
     */
    public static byte[] decrypt(byte[] fileData, String playAuth) throws GeneralSecurityException {
        if (fileData == null || fileData.length < 16) {
            throw new DecryptException("audio data too short");
        }
        byte[] key = deriveContentKey(playAuth);

        Box moov = findBox(fileData, "moov", 0, fileData.length);
        if (moov == null) throw new DecryptException("moov box not found");

        Box stbl = findBox(fileData, "stbl", moov.offset, moov.offset + moov.size);
        if (stbl == null) {
            Box trak = findBox(fileData, "trak", moov.offset + 8, moov.offset + moov.size);
            Box mdia = trak == null ? null
                    : findBox(fileData, "mdia", trak.offset + 8, trak.offset + trak.size);
            Box minf = mdia == null ? null
                    : findBox(fileData, "minf", mdia.offset + 8, mdia.offset + mdia.size);
            stbl = minf == null ? null
                    : findBox(fileData, "stbl", minf.offset + 8, minf.offset + minf.size);
        }
        if (stbl == null) throw new DecryptException("stbl box not found");

        Box stsz = findBox(fileData, "stsz", stbl.offset + 8, stbl.offset + stbl.size);
        if (stsz == null) throw new DecryptException("stsz box not found");
        long[] sampleSizes = parseStsz(stsz.data);

        Box senc = findBox(fileData, "senc", moov.offset + 8, moov.offset + moov.size);
        if (senc == null) {
            senc = findBox(fileData, "senc", stbl.offset + 8, stbl.offset + stbl.size);
        }
        if (senc == null) throw new DecryptException("senc box not found");
        int ivSize = perSampleIvSize(fileData, stbl.offset, stbl.offset + stbl.size);
        SampleEncryption[] samples = parseSenc(senc.data, ivSize);

        Box mdat = findBox(fileData, "mdat", 0, fileData.length);
        if (mdat == null) throw new DecryptException("mdat box not found");

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] out = fileData.clone();
        int readPtr = mdat.offset + 8;
        int payloadLimit = Math.min(mdat.offset + mdat.size, out.length);
        for (int i = 0; i < sampleSizes.length; i++) {
            int size = (int) sampleSizes[i];
            if (size <= 0 || readPtr + size > payloadLimit) break;
            if (i < samples.length) {
                decryptSample(cipher, key, out, readPtr, size, samples[i]);
            }
            readPtr += size;
        }

        Box stsd = findBox(fileData, "stsd", stbl.offset + 8, stbl.offset + stbl.size);
        if (stsd != null) {
            int idx = indexOf(out, stsd.offset, stsd.offset + stsd.size, "enca");
            if (idx >= 0) {
                byte[] original = originalFormat(out, stsd.offset, stsd.offset + stsd.size);
                System.arraycopy(original, 0, out, idx, 4);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- key derivation

    /** Derives the 16-byte AES content key from the play-auth (spadeA) blob. */
    static byte[] deriveContentKey(String playAuth) throws DecryptException {
        if (playAuth == null || playAuth.isEmpty()) throw new DecryptException("empty play auth");
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(playAuth);
        } catch (IllegalArgumentException e) {
            throw new DecryptException("invalid play auth encoding");
        }
        if (raw.length < 3) throw new DecryptException("play auth too short");

        int padding = ((raw[0] ^ raw[1] ^ raw[2]) & 0xFF) - 48;
        if (padding < 0 || raw.length < padding + 2) {
            throw new DecryptException("invalid play auth padding");
        }
        int innerLen = raw.length - padding - 1;
        byte[] inner = new byte[innerLen];
        System.arraycopy(raw, 1, inner, 0, innerLen);

        byte[] scrambled = unscramble(inner);
        int skip = decodeBase36(scrambled[0]);
        if (skip == 0xFF) throw new DecryptException("invalid play auth prefix");
        int end = 1 + (raw.length - padding - 2) - skip;
        if (end < 1 || end > scrambled.length) {
            throw new DecryptException("play auth index out of bounds");
        }
        String hex = new String(scrambled, 1, end - 1, java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] key;
        try {
            key = decodeHex(hex);
        } catch (IllegalArgumentException e) {
            throw new DecryptException("content key is not hex");
        }
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new DecryptException("unexpected content key length");
        }
        return key;
    }

    private static byte[] unscramble(byte[] inner) {
        byte[] buff = new byte[inner.length + 2];
        buff[0] = (byte) 0xFA;
        buff[1] = 0x55;
        System.arraycopy(inner, 0, buff, 2, inner.length);
        byte[] result = new byte[inner.length];
        for (int i = 0; i < result.length; i++) {
            int v = ((inner[i] & 0xFF) ^ (buff[i] & 0xFF)) - Integer.bitCount(i) - 21;
            while (v < 0) v += 255;
            result[i] = (byte) v;
        }
        return result;
    }

    private static int decodeBase36(byte b) {
        int c = b & 0xFF;
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'z') return c - 'a' + 10;
        return 0xFF;
    }

    private static byte[] decodeHex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex digit");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ---------------------------------------------------------------- sample decryption

    private static void decryptSample(Cipher cipher, byte[] key, byte[] data,
                                      int offset, int size, SampleEncryption sample)
            throws GeneralSecurityException {
        byte[] iv = new byte[16];
        System.arraycopy(sample.iv, 0, iv, 0, Math.min(sample.iv.length, 16));
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

        if (sample.subsamples == null || sample.subsamples.length == 0) {
            transform(cipher, data, offset, size);
            return;
        }
        int pos = offset;
        int end = offset + size;
        for (int[] sub : sample.subsamples) {
            int clear = Math.min(sub[0], end - pos);
            pos += clear; // clear bytes are deliberately left untouched
            if (pos >= end) break;
            int encrypted = (int) Math.min(sub[1], (long) (end - pos));
            transform(cipher, data, pos, encrypted);
            pos += encrypted;
            if (pos >= end) break;
        }
    }

    /** CTR is a stream cipher: update() keeps the keystream continuous across calls. */
    private static void transform(Cipher cipher, byte[] data, int offset, int length)
            throws GeneralSecurityException {
        if (length <= 0) return;
        byte[] plain = cipher.update(data, offset, length);
        if (plain == null || plain.length != length) {
            throw new DecryptException("unexpected AES-CTR output length");
        }
        System.arraycopy(plain, 0, data, offset, length);
    }

    // ---------------------------------------------------------------- ISO BMFF parsing

    private static final class Box {
        final int offset;
        final int size;
        final byte[] data;

        Box(int offset, int size, byte[] data) {
            this.offset = offset;
            this.size = size;
            this.data = data;
        }
    }

    private static Box findBox(byte[] data, String type, int start, int end) {
        int limit = Math.min(end, data.length);
        int pos = Math.max(start, 0);
        while (pos + 8 <= limit) {
            long size64 = readU32(data, pos);
            if (size64 < 8) break;
            if (size64 > limit - pos) break;
            int size = (int) size64;
            if (asciiEquals(data, pos + 4, type)) {
                byte[] body = new byte[size - 8];
                System.arraycopy(data, pos + 8, body, 0, size - 8);
                return new Box(pos, size, body);
            }
            pos += size;
        }
        return null;
    }

    private static Box findBoxDeep(byte[] data, String type, int start, int end) {
        int limit = Math.min(end, data.length);
        int pos = Math.max(start, 0);
        while (pos + 8 <= limit) {
            long size64 = readU32(data, pos);
            int header = 8;
            if (size64 == 1) {
                if (pos + 16 > limit) break;
                size64 = readU64(data, pos + 8);
                header = 16;
            }
            if (size64 < header || size64 > limit - pos) break;
            int size = (int) size64;
            String current = ascii(data, pos + 4);
            if (type.equals(current)) {
                byte[] body = new byte[size - header];
                System.arraycopy(data, pos + header, body, 0, size - header);
                return new Box(pos, size, body);
            }
            int childOffset = childOffset(current, pos, header);
            if (childOffset > 0 && childOffset < pos + size) {
                Box found = findBoxDeep(data, type, childOffset, pos + size);
                if (found != null) return found;
            }
            pos += size;
        }
        return null;
    }

    private static int childOffset(String type, int offset, int header) {
        switch (type) {
            case "moov":
            case "trak":
            case "mdia":
            case "minf":
            case "stbl":
            case "sinf":
            case "schi":
                return offset + header;
            case "stsd":
                return offset + header + 8;
            case "enca":
            case "mp4a":
            case "alac":
            case "fLaC":
                return offset + header + 28;
            default:
                return 0;
        }
    }

    private static long[] parseStsz(byte[] d) {
        if (d.length < 12) return new long[0];
        long fixed = readU32(d, 4);
        long count = readU32(d, 8);
        if (count < 0 || count > (d.length - 12) / 4 + 1 || count > Integer.MAX_VALUE) {
            count = Math.max(0, (d.length - 12) / 4);
        }
        long[] sizes = new long[(int) count];
        if (fixed != 0) {
            for (int i = 0; i < sizes.length; i++) sizes[i] = fixed;
        } else {
            for (int i = 0; i < sizes.length; i++) {
                int p = 12 + i * 4;
                sizes[i] = p + 4 <= d.length ? readU32(d, p) : 0;
            }
        }
        return sizes;
    }

    private static int perSampleIvSize(byte[] data, int start, int end) {
        Box tenc = findBoxDeep(data, "tenc", start, end);
        if (tenc == null || tenc.data.length < 8) return 8;
        int iv = tenc.data[7] & 0xFF;
        return (iv == 8 || iv == 16) ? iv : 8;
    }

    private static final class SampleEncryption {
        final byte[] iv;
        final int[][] subsamples; // [clearBytes, encryptedBytes]

        SampleEncryption(byte[] iv, int[][] subsamples) {
            this.iv = iv;
            this.subsamples = subsamples;
        }
    }

    private static SampleEncryption[] parseSenc(byte[] d, int ivSize) {
        if (d.length < 8) return new SampleEncryption[0];
        if (ivSize != 8 && ivSize != 16) ivSize = 8;
        long flags = readU32(d, 0) & 0x00FFFFFFL;
        boolean hasSubsamples = (flags & 0x02L) != 0;
        long count = readU32(d, 4);
        if (count < 0 || count > Integer.MAX_VALUE) count = 0;
        SampleEncryption[] samples = new SampleEncryption[(int) Math.min(count, d.length / ivSize + 1)];
        int ptr = 8;
        for (int i = 0; i < samples.length; i++) {
            if (ptr + ivSize > d.length) {
                return java.util.Arrays.copyOf(samples, i);
            }
            byte[] iv = new byte[ivSize];
            System.arraycopy(d, ptr, iv, 0, ivSize);
            ptr += ivSize;
            int[][] subs = null;
            if (hasSubsamples) {
                if (ptr + 2 > d.length) return java.util.Arrays.copyOf(samples, i);
                int subCount = (int) readU16(d, ptr);
                ptr += 2;
                if (subCount < 0 || ptr + (long) subCount * 6 > d.length) {
                    return java.util.Arrays.copyOf(samples, i);
                }
                subs = new int[subCount][2];
                for (int j = 0; j < subCount; j++) {
                    subs[j][0] = (int) readU16(d, ptr);
                    subs[j][1] = (int) readU32(d, ptr + 2);
                    ptr += 6;
                }
            }
            samples[i] = new SampleEncryption(iv, subs);
        }
        return samples;
    }

    private static byte[] originalFormat(byte[] data, int start, int end) {
        int idx = indexOf(data, start, Math.min(end, data.length), "frma");
        if (idx < 4 || idx + 8 > data.length) return new byte[] {'m', 'p', '4', 'a'};
        int boxStart = idx - 4;
        long size = readU32(data, boxStart);
        if (size < 12 || boxStart + size > data.length) return new byte[] {'m', 'p', '4', 'a'};
        byte[] out = new byte[4];
        System.arraycopy(data, idx + 4, out, 0, 4);
        return out;
    }

    // ---------------------------------------------------------------- little helpers

    private static long readU32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((long) (d[p + 1] & 0xFF) << 16)
                | ((long) (d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFFL);
    }

    private static long readU64(byte[] d, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[p + i] & 0xFFL);
        return v;
    }

    private static long readU16(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 8) | (d[p + 1] & 0xFFL);
    }

    private static boolean asciiEquals(byte[] d, int p, String s) {
        if (p + 4 > d.length || s.length() != 4) return false;
        for (int i = 0; i < 4; i++) if (d[p + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static String ascii(byte[] d, int p) {
        if (p + 4 > d.length) return "";
        return new String(new byte[] {d[p], d[p + 1], d[p + 2], d[p + 3]},
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] data, int start, int end, String needle) {
        int limit = Math.min(end, data.length) - 4;
        for (int i = Math.max(start, 0); i <= limit; i++) {
            if (asciiEquals(data, i, needle)) return i;
        }
        return -1;
    }
}
