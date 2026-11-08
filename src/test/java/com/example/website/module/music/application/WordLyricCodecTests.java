package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.LyricInfo;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class WordLyricCodecTests {
    // Synthetic text encrypted by an independent QRC implementation; no song fixture.
    static final String QRC = "ee7befb746f93831d42dcdf2e21f0d5d977de00c85449a7c535da3bcf1beb3ca54d5d801d40005b77f924be08aef9bfc6b40d6f294403ebedc56c7fc6571f664e631053a338b76973dd80b173df09edf2804d534db992baaf2a361c97dedc84c5fab77b58f863cc4ed7dff291cb886087b63803d3bda566e";
    static final String YRC = "[1000,1200](1000,400,0)你(1400,200,0)好 (1800,400,0)世界\n[3000,500](3000,500,0)测试";

    @Test void decryptsCloudQrcAndKeepsAbsoluteWordTimesAndSpaces() throws Exception {
        LyricInfo lyric = WordLyricCodec.decodeQq(QRC);
        assertEquals(YRC, lyric.getKaraokeLyrics());
        assertEquals("[00:01.000]你好 世界\n[00:03.000]测试", lyric.getLineLyrics());
    }

    @Test void parsesQrcEntitiesOffsetsAndActualWordEnd() {
        LyricInfo lyric = WordLyricCodec.parseQq("<Lyric_1 LyricContent=\"[offset:100]\n[1000,500]A &amp; B(1000,600)\"/>");
        assertEquals("[1100,600](1100,600,0)A & B", lyric.getKaraokeLyrics());
    }

    @Test void keepsOrdinaryLyricsWhenAnyWordRowIsMalformed() {
        LyricInfo lyric = WordLyricCodec.parseQq("[1000,1000]A(1400,100)B(1000,100)\n[3000,500]C(3000,500)");
        assertEquals("[00:01.000]AB\n[00:03.000]C", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
    }

    @Test void supportsLineOnlyQqResponseWithoutInventingWordTimes() {
        LyricInfo lyric = WordLyricCodec.parseQq("[00:01.00]普通歌词");
        assertEquals("[00:01.00]普通歌词", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
    }

    @Test void decodesKuwoEnvelopeAndOctalTimingFactors() throws Exception {
        // [kuwo:034] = octal 28, start divisor 4, duration divisor 16.
        LyricInfo lyric = WordLyricCodec.decodeKuwo(kuwoEnvelope(
                "[kuwo:034]\n[offset:100]\n[00:01.000]<3200,-3200>你<1600,0>好 <4000,2400>世界"));
        assertEquals("[00:01.100]你好 世界", lyric.getLineLyrics());
        assertEquals("[1100,1700](1100,400,0)你(1500,100,0)好 (2700,100,0)世界", lyric.getKaraokeLyrics());
    }

    @Test void derivesKuwoFactorsPerPayloadWithoutSharedState() {
        LyricInfo a = WordLyricCodec.parseKuwo("[kuwo:127]\n[00:02.000]<2681,-2681>甲<6151,-23>乙");
        WordLyricCodec.parseKuwo("[kuwo:034]\n[00:01.000]<3200,-3200>丙");
        assertEquals("[2000,824](2000,383,0)甲(2383,441,0)乙", a.getKaraokeLyrics());
    }

    @Test void invalidKuwoFactorsStillPreserveOrdinaryLyrics() {
        LyricInfo lyric = WordLyricCodec.parseKuwo("[kuwo:0]\n[00:01.000]<3200,-3200>普通");
        assertEquals("[00:01.000]普通", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
    }

    @Test void rejectsTruncatedAndOversizedPayloads() {
        assertThrows(Exception.class, () -> WordLyricCodec.decodeQq("aabb"));
        assertThrows(Exception.class, () -> WordLyricCodec.decodeQq("zzzzzzzzzzzzzzzz"));
        assertThrows(Exception.class, () -> WordLyricCodec.decodeKuwo("tp=none\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(Exception.class, () -> WordLyricCodec.decodeKuwo(kuwoEnvelope(new String(new char[2_100_000]).replace('\0', 'A'))));
    }

    static byte[] kuwoEnvelope(String text) throws Exception {
        byte[] raw = text.getBytes(Charset.forName("GB18030"));
        byte[] key = "yeelion".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < raw.length; i++) raw[i] ^= key[i % key.length];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("tp=content\r\nlrcx=1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) { z.write(Base64.getEncoder().encode(raw)); }
        return out.toByteArray();
    }
}
