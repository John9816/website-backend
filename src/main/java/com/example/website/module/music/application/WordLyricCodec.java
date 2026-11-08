package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.LyricInfo;
import org.jsoup.parser.Parser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/** Converts provider word timings to the absolute-millisecond YRC contract used by the app. */
final class WordLyricCodec {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TIME = 24 * 60 * 60 * 1000;
    private static final byte[] KUWO_KEY = "yeelion".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern QRC_CONTENT = Pattern.compile("LyricContent=\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern QRC_ROW = Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
    private static final Pattern QRC_WORD = Pattern.compile("\\((-?\\d{1,9}),(-?\\d{1,9})\\)");
    private static final Pattern LRC_ROW = Pattern.compile("^\\[(\\d{1,3}):([0-5]?\\d)(?:[.:](\\d{1,3}))?](.*)$");
    private static final Pattern KUWO_WORD = Pattern.compile("<(-?\\d{1,10}),(-?\\d{1,10})(?:,-?\\d{1,10})?>");
    private static final Pattern KUWO_VERSION = Pattern.compile("(?m)^\\[kuwo:([0-7]{1,3})]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d{1,8})]\\s*$");
    private WordLyricCodec() {}

    static LyricInfo decodeQq(String hex) throws IOException {
        if (hex == null || hex.isEmpty() || hex.length() > MAX_BYTES || hex.length() % 16 != 0) {
            throw new IOException("Invalid QRC size");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int a = Character.digit(hex.charAt(i * 2), 16), b = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) throw new IOException("Invalid QRC hex");
            bytes[i] = (byte)((a << 4) | b);
        }
        return parseQq(new String(inflate(QrcCipher.decrypt(bytes)), StandardCharsets.UTF_8));
    }

    static LyricInfo parseQq(String text) {
        checkText(text);
        Matcher xml = QRC_CONTENT.matcher(text);
        if (xml.find()) text = Parser.unescapeEntities(xml.group(1), true);
        long offset = offset(text);
        List<Row> rows = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher row = QRC_ROW.matcher(line.trim());
            if (!row.matches()) continue;
            long start = Long.parseLong(row.group(1)) + offset;
            String content = row.group(3);
            Row out = new Row(start, QRC_WORD.matcher(content).replaceAll(""));
            Matcher word = QRC_WORD.matcher(content);
            int previousEnd = 0;
            while (word.find()) {
                out.words.add(new Word(Long.parseLong(word.group(1)) + offset,
                        Long.parseLong(word.group(2)), content.substring(previousEnd, word.start())));
                previousEnd = word.end();
            }
            if (!content.substring(previousEnd).trim().isEmpty()) out.words.clear();
            rows.add(out);
        }
        if (rows.isEmpty()) {
            // Some QQ songs only have ordinary LRC, even on the enhanced endpoint.
            boolean timed = false;
            for (String line : text.split("\\r?\\n")) if (LRC_ROW.matcher(line).matches()) timed = true;
            return new LyricInfo(timed ? text.trim() : null, null);
        }
        return render(rows);
    }

    static String kuwoQuery(String songId) {
        byte[] data = ("user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_" + songId + "&lrcx=1")
                .getBytes(StandardCharsets.UTF_8);
        xorKuwo(data);
        return Base64.getEncoder().encodeToString(data);
    }

    static LyricInfo decodeKuwo(byte[] envelope) throws IOException {
        if (envelope.length > MAX_BYTES) throw new IOException("Kuwo lyric too large");
        int boundary = -1;
        for (int i = 0; i < Math.min(envelope.length - 3, 4096); i++) {
            if (envelope[i] == '\r' && envelope[i + 1] == '\n' && envelope[i + 2] == '\r' && envelope[i + 3] == '\n') {
                boundary = i; break;
            }
        }
        if (boundary < 0 || !new String(envelope, 0, boundary, StandardCharsets.US_ASCII).startsWith("tp=content\r\n")) {
            throw new IOException("Missing Kuwo lyric envelope");
        }
        byte[] compressed = java.util.Arrays.copyOfRange(envelope, boundary + 4, envelope.length);
        byte[] data;
        try { data = Base64.getDecoder().decode(new String(inflate(compressed), StandardCharsets.US_ASCII).trim()); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid Kuwo base64", ex); }
        xorKuwo(data);
        String text = Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(data)).toString();
        return parseKuwo(text);
    }

    static LyricInfo parseKuwo(String text) {
        checkText(text);
        Matcher version = KUWO_VERSION.matcher(text);
        int factors = version.find() ? Integer.parseInt(version.group(1), 8) : 0;
        int startFactor = factors / 10, durationFactor = factors % 10;
        long offset = offset(text);
        List<Row> rows = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher row = LRC_ROW.matcher(line);
            if (!row.matches()) continue;
            String fraction = row.group(3) == null ? "0" : row.group(3);
            long start = Long.parseLong(row.group(1)) * 60000 + Long.parseLong(row.group(2)) * 1000
                    + Long.parseLong((fraction + "00").substring(0, 3)) + offset;
            String content = row.group(4);
            Row out = new Row(start, KUWO_WORD.matcher(content).replaceAll(""));
            if (startFactor > 0 && durationFactor > 0) {
                Matcher word = KUWO_WORD.matcher(content);
                Word previous = null;
                int textStart = 0;
                boolean hasPrefix = false;
                while (word.find()) {
                    if (previous != null) previous.text = content.substring(textStart, word.start());
                    else hasPrefix = !content.substring(0, word.start()).trim().isEmpty();
                    long a = Long.parseLong(word.group(1)), b = Long.parseLong(word.group(2));
                    previous = new Word(start + Math.abs(a + b) / (startFactor * 2L),
                            Math.abs(a - b) / (durationFactor * 2L), "");
                    out.words.add(previous);
                    textStart = word.end();
                }
                if (previous != null) previous.text = content.substring(textStart);
                if (hasPrefix) out.words.clear();
            }
            rows.add(out);
        }
        return render(rows);
    }

    private static LyricInfo render(List<Row> rows) {
        rows.sort(Comparator.comparingLong(row -> row.start));
        StringBuilder line = new StringBuilder(), karaoke = new StringBuilder();
        boolean allTimed = true;
        for (Row row : rows) {
            if (row.start < 0 || row.start > MAX_TIME || row.text.trim().isEmpty()) continue;
            appendLine(line, String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", row.start / 60000,
                    row.start / 1000 % 60, row.start % 1000, row.text));
            boolean valid = !row.words.isEmpty();
            long previous = row.start, end = row.start;
            StringBuilder content = new StringBuilder();
            for (Word word : row.words) {
                if (word.start < previous || word.start > MAX_TIME || word.duration < 0 || word.duration > MAX_TIME
                        || word.start + word.duration > MAX_TIME) valid = false;
                previous = word.start;
                end = Math.max(end, word.start + word.duration);
                content.append('(').append(word.start).append(',').append(word.duration).append(",0)").append(word.text);
            }
            // If a provider row cannot be represented faithfully, keep the complete ordinary lyric.
            if (!valid || end <= row.start) allTimed = false;
            else appendLine(karaoke, "[" + row.start + "," + (end - row.start) + "]" + content);
        }
        return new LyricInfo(line.length() == 0 ? null : line.toString(),
                allTimed && karaoke.length() > 0 ? karaoke.toString() : null);
    }

    static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int count;
        while ((count = input.read(block)) != -1) {
            if (count > MAX_BYTES - output.size()) throw new IOException("Lyric exceeds size limit");
            output.write(block, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] inflate(byte[] bytes) throws IOException {
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(bytes))) { return readLimited(input); }
    }
    private static void xorKuwo(byte[] bytes) { for (int i = 0; i < bytes.length; i++) bytes[i] ^= KUWO_KEY[i % KUWO_KEY.length]; }
    private static void checkText(String text) {
        if (text == null || text.length() > MAX_BYTES) throw new IllegalArgumentException("Invalid lyric text size");
    }
    private static long offset(String text) { Matcher m = OFFSET.matcher(text); return m.find() ? Long.parseLong(m.group(1)) : 0; }
    private static void appendLine(StringBuilder out, String text) { if (out.length() > 0) out.append('\n'); out.append(text); }
    private static final class Row {
        final long start; final String text; final List<Word> words = new ArrayList<>();
        Row(long start, String text) { this.start = start; this.text = text; }
    }
    private static final class Word {
        final long start, duration; String text;
        Word(long start, long duration, String text) { this.start = start; this.duration = duration; this.text = text; }
    }
}
