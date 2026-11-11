package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Serves plain, browser-playable Qishui audio by composing the whole TuneFree v1 chain:
 * obtain an access token, call {@code /v1/parse}, download the CENC-encrypted MP4, decrypt it
 * in-process, and cache the plain bytes.
 *
 * <p>The upstream play URL is never handed to the browser: it is short-lived, on a foreign host
 * (CORS), and encrypted. Instead {@link QishuiParseClient} returns a signed local stream URL,
 * and this service produces the bytes behind it.
 *
 * <p>First-play latency used to be dominated by waiting for the whole encrypted track to
 * arrive before a single byte could be returned. The upstream MP4 is faststart (ftyp + moov
 * at the head, ~90 KB here), and CENC uses AES-CTR with a per-sample IV, so a load now runs on a
 * background task that downloads, decrypts sample-by-sample and writes the plain file as it
 * goes. As soon as the header is written the request thread returns an {@link AudioFile}
 * handle; {@link AudioFile#writeRange} waits only for the specific bytes it needs. Range reads
 * are still positional {@code FileChannel} reads, so no track is ever materialised whole in the
 * 768 MB heap. Concurrent requests for one key share a single load task.
 *
 * <p>Anything that breaks the faststart assumption (moov at the tail, unknown Content-Length,
 * oversized header, I/O error) falls back to the original whole-track download + one-shot
 * decrypt. Disk-caching-disabled deployments keep the original in-memory behaviour.
 */
@Slf4j
@Service
public class QishuiStreamService implements DisposableBean {

    private static final String PARSE_URL = "https://tunefree.sayqz.com/v1/parse";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_AUDIO_BYTES = 48L * 1024 * 1024;   // guard against runaway responses
    private static final long DISK_CACHE_MAX_BYTES = 1024L * 1024 * 1024;
    private static final long CACHE_TTL_SEC = 1100L;

    private static final int MAX_HEADER_BYTES = 512 * 1024;  // faststart moov fits here comfortably
    private static final int READ_CHUNK = 16 * 1024;
    private static final long HEADER_TIMEOUT_MS = 45_000L;
    private static final long READY_TIMEOUT_MS = 60_000L;

    private final TuneFreeV1AuthService auth;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final Cache<String, AudioFile> plainCache;
    private final QishuiDiskCache diskCache;
    private final ConcurrentHashMap<String, LoadTask> loads = new ConcurrentHashMap<>();
    private final ExecutorService loaders = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "qishui-stream-loader");
        t.setDaemon(true);
        return t;
    });

    public QishuiStreamService(
            TuneFreeV1AuthService auth,
            @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient baseClient,
            ObjectMapper mapper,
            @Value("${app.music.qishui.cache-dir:/opt/website/cache/qishui-audio}") String cacheDir) {
        this.auth = auth;
        this.http = baseClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mapper = mapper;
        this.diskCache = new QishuiDiskCache(cacheDir, DISK_CACHE_MAX_BYTES, CACHE_TTL_SEC);
        this.plainCache = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(Duration.ofSeconds(CACHE_TTL_SEC))
                .build();
    }

    @Override
    public void destroy() {
        loaders.shutdownNow();
    }

    /**
     * Returns a handle to the decrypted track. A cache hit is a complete on-disk file; a miss
     * starts a single streaming load and returns as soon as the MP4 header is on disk.
     */
    public AudioFile open(String trackId, String quality) {
        String key = cacheKey(trackId, quality);

        AudioFile cached = plainCache.getIfPresent(key);
        if (cached != null && cached.isValid()) {
            return cached;
        }
        if (cached != null) {
            // The disk entry was evicted/trimmed while the handle stayed cached: force a reload.
            plainCache.invalidate(key);
        }

        if (!diskCache.isEnabled()) {
            // Preserve the original in-memory behaviour when there is no writable cache dir.
            byte[] plain = resolvePlaintext(trackId, quality);
            return new AudioFile(null, plain, plain.length);
        }

        Path disk = diskCache.resolve(key);
        if (disk != null) {
            try {
                AudioFile hit = new AudioFile(disk, null, Files.size(disk));
                plainCache.put(key, hit);
                return hit;
            } catch (IOException e) {
                log.warn("Qishui disk cache stat failed key={}: {}", key, e.toString());
            }
        }

        LoadTask mine = new LoadTask(key, trackId, quality);
        LoadTask task = loads.putIfAbsent(key, mine);
        if (task == null) {
            task = mine;
            loaders.execute(mine);
        }
        try {
            task.awaitHeader();
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "Qishui audio header not ready: " + e.getMessage(), e);
        }
        return new AudioFile(task.staging, null, task.totalBytes, task);
    }

    /** Streaming load task: one upstream connection, decrypt and write as bytes arrive. */
    private final class LoadTask implements Runnable {
        final String key;
        final String trackId;
        final String quality;
        final Path staging;
        final Object monitor = new Object();

        volatile long totalBytes = -1L;
        volatile long readyBytes = 0L;
        volatile boolean headerReady = false;
        volatile boolean done = false;
        volatile IOException failure = null;
        volatile Path livePath;

        LoadTask(String key, String trackId, String quality) {
            this.key = key;
            this.trackId = trackId;
            this.quality = quality;
            this.staging = diskCache.staging(key);
            this.livePath = staging;
        }

        @Override
        public void run() {
            long started = System.currentTimeMillis();
            try {
                ParseResult parsed = parseWithRetry(trackId, quality);
                streamInto(parsed, started, System.currentTimeMillis());
            } catch (Exception e) {
                fail(e);
            }
        }

        private void streamInto(ParseResult parsed, long started, long parsedAt) throws Exception {
            Request req = new Request.Builder().url(parsed.playUrl)
                    .header("User-Agent", "tunefree-next-bff/1.0")
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                long connectedAt = System.currentTimeMillis();
                ResponseBody rb = resp.body();
                if (resp.code() < 200 || resp.code() >= 300 || rb == null) {
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                            "Qishui audio download returned HTTP " + resp.code());
                }
                long declared = rb.contentLength();
                if (declared <= 0 || declared > MAX_AUDIO_BYTES) {
                    // No trustworthy length -> Content-Range would be a guess. Use the old path.
                    fallbackWhole(parsed);
                    return;
                }
                totalBytes = declared;

                BufferedSource source = rb.source();
                byte[] header = new byte[READ_CHUNK];
                int headerLen = 0;
                long[] mdat = null;
                while (mdat == null) {
                    if (headerLen >= MAX_HEADER_BYTES) {
                        fallbackWhole(parsed);
                        return;
                    }
                    if (header.length < headerLen + READ_CHUNK) {
                        header = Arrays.copyOf(header, headerLen + READ_CHUNK);
                    }
                    int n = source.read(header, headerLen, READ_CHUNK);
                    if (n < 0) break;
                    headerLen += n;
                    mdat = QishuiCencDecryptor.StreamPlan.locateMdat(header, headerLen);
                    if (mdat != null && mdat[0] + 8 > headerLen) {
                        mdat = null; // the 8-byte mdat header itself is not complete yet
                    }
                }
                if (mdat == null) {
                    fallbackWhole(parsed);
                    return;
                }

                long headerReadAt = System.currentTimeMillis();
                log.info("Qishui stream timing quality={} parse={}ms connect={}ms headerRead={}ms "
                                + "headerBytes={} size={}",
                        quality, parsedAt - started, connectedAt - parsedAt,
                        headerReadAt - connectedAt, headerLen, declared);
                QishuiCencDecryptor.StreamPlan plan = QishuiCencDecryptor.StreamPlan.parse(
                        header, headerLen, parsed.playAuth, mdat[0], mdat[1], declared);
                int payloadStart = plan.mdatPayloadStart();
                int prefixEnd = Math.min(headerLen, payloadStart);
                plan.patchHeader(header);

                try (FileChannel channel = FileChannel.open(staging,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    writeFully(channel, header, 0, prefixEnd);
                    markReady(prefixEnd);
                    markHeaderReady();
                    log.info("Qishui stream header ready quality={} headerBytes={} total={}",
                            quality, prefixEnd, declared);

                    StreamInput in = new StreamInput(header, prefixEnd, headerLen, source);
                    long readPtr = prefixEnd;
                    long[] sizes = plan.sampleSizes();
                    byte[] sampleBuf = new byte[READ_CHUNK];
                    for (int i = 0; i < sizes.length; i++) {
                        int size = (int) sizes[i];
                        if (size <= 0 || readPtr + size > plan.payloadLimit()) break;
                        if (sampleBuf.length < size) sampleBuf = new byte[size];
                        if (!in.readFully(sampleBuf, 0, size)) break;
                        plan.decryptSample(sampleBuf, 0, size, i);
                        writeFully(channel, sampleBuf, 0, size);
                        readPtr += size;
                        markReady(readPtr);
                    }
                    // Any trailing bytes after the last sample are container padding: plain.
                    byte[] rest = new byte[READ_CHUNK];
                    int n;
                    while ((n = in.readSome(rest)) > 0) {
                        writeFully(channel, rest, 0, n);
                        readPtr += n;
                        markReady(readPtr);
                    }
                }

                long written = Files.size(staging);
                if (written != declared) {
                    throw new IOException("Qishui streaming load truncated: wrote " + written
                            + " of " + declared);
                }
                finish(declared);
                log.info("Qishui stream ready quality={} bytes={} (streaming)", quality, written);
            }
        }

        private void fallbackWhole(ParseResult parsed) throws Exception {
            byte[] encrypted = download(parsed.playUrl);
            byte[] plain = QishuiCencDecryptor.decrypt(encrypted, parsed.playAuth);
            diskCache.write(key, plain);
            totalBytes = plain.length;
            livePath = diskCache.target(key);
            if (livePath == null) livePath = staging;
            synchronized (monitor) {
                readyBytes = plain.length;
                headerReady = true;
                done = true;
                monitor.notifyAll();
            }
            plainCache.put(key, new AudioFile(livePath, null, plain.length));
            loads.remove(key, this);
            log.info("Qishui stream ready quality={} bytes={} (whole-track fallback)",
                    quality, plain.length);
        }

        private void finish(long length) {
            boolean published = diskCache.publish(key, staging);
            Path finalPath = published ? diskCache.target(key) : staging;
            livePath = finalPath;
            synchronized (monitor) {
                readyBytes = length;
                headerReady = true;
                done = true;
                monitor.notifyAll();
            }
            if (finalPath != null) {
                plainCache.put(key, new AudioFile(finalPath, null, length));
            }
            loads.remove(key, this);
        }

        private void fail(Exception e) {
            IOException io = e instanceof IOException ? (IOException) e
                    : new IOException(e.toString(), e);
            log.warn("Qishui streaming load failed key={}: {}", key, e.toString());
            synchronized (monitor) {
                failure = io;
                done = true;
                monitor.notifyAll();
            }
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            loads.remove(key, this);
        }

        private void markReady(long n) {
            synchronized (monitor) {
                if (n > readyBytes) {
                    readyBytes = n;
                    monitor.notifyAll();
                }
            }
        }

        private void markHeaderReady() {
            synchronized (monitor) {
                headerReady = true;
                monitor.notifyAll();
            }
        }

        void awaitHeader() throws IOException {
            long deadline = System.currentTimeMillis() + HEADER_TIMEOUT_MS;
            synchronized (monitor) {
                while (!headerReady && !done && failure == null) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("timed out waiting for MP4 header");
                    try {
                        monitor.wait(Math.min(200L, left));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted waiting for MP4 header", e);
                    }
                }
                if (failure != null && !headerReady) throw new IOException("audio load failed", failure);
                if (totalBytes <= 0) throw new IOException("unknown audio length");
            }
        }

        void awaitReady(long need) throws IOException {
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            synchronized (monitor) {
                while (readyBytes < need && !done && failure == null) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        throw new IOException("timed out waiting for audio byte " + need);
                    }
                    try {
                        monitor.wait(Math.min(200L, left));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted waiting for audio", e);
                    }
                }
                if (failure != null && readyBytes < need) {
                    throw new IOException("audio load failed", failure);
                }
            }
        }

        Path currentPath() {
            return livePath;
        }
    }

    /** Feeds bytes from the header prefix first, then from the still-open upstream stream. */
    private static final class StreamInput {
        private final byte[] prefix;
        private final int prefixEnd;
        private final BufferedSource source;
        private int prefixPos;

        StreamInput(byte[] prefix, int from, int end, BufferedSource source) {
            this.prefix = prefix;
            this.prefixPos = from;
            this.prefixEnd = end;
            this.source = source;
        }

        boolean readFully(byte[] dst, int off, int len) throws IOException {
            int filled = 0;
            while (filled < len) {
                if (prefixPos < prefixEnd) {
                    int n = Math.min(len - filled, prefixEnd - prefixPos);
                    System.arraycopy(prefix, prefixPos, dst, off + filled, n);
                    prefixPos += n;
                    filled += n;
                } else {
                    int n = source.read(dst, off + filled, len - filled);
                    if (n < 0) return false;
                    filled += n;
                }
            }
            return true;
        }

        int readSome(byte[] dst) throws IOException {
            if (prefixPos < prefixEnd) {
                int n = Math.min(dst.length, prefixEnd - prefixPos);
                System.arraycopy(prefix, prefixPos, dst, 0, n);
                prefixPos += n;
                return n;
            }
            return source.read(dst);
        }
    }

    private static void writeFully(FileChannel channel, byte[] data, int off, int len)
            throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data, off, len);
        while (bb.hasRemaining()) {
            channel.write(bb);
        }
    }

    /**
     * Upstream {@code flac24bit} returns {@code actualQuality=flac} and byte-identical audio, so
     * both qualities share one cache entry. This halves the download/decrypt/memory cost for the
     * Hi-Res option without changing what the browser receives.
     */
    static String cacheKey(String trackId, String quality) {
        return trackId + "|" + normalizeQuality(quality);
    }

    static String normalizeQuality(String quality) {
        if (quality == null) {
            return "";
        }
        return "flac24bit".equalsIgnoreCase(quality) ? "flac" : quality;
    }

    private byte[] resolvePlaintext(String trackId, String quality) {
        ParseResult parsed = parseWithRetry(trackId, quality);
        byte[] encrypted = download(parsed.playUrl);
        try {
            byte[] plain = QishuiCencDecryptor.decrypt(encrypted, parsed.playAuth);
            log.info("Qishui stream ready trackLength={} quality={} bytes={}",
                    trackId.length(), quality, plain.length);
            return plain;
        } catch (GeneralSecurityException e) {
            log.warn("Qishui CENC decrypt failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "failed to decode Qishui audio: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve a Qishui track to its final (post-302) CDN URL plus the CENC play auth, so the
     * browser can fetch the encrypted bytes directly and decrypt them client-side. This keeps the
     * audio bytes off our ECS egress entirely — the backend only signs and points.
     */
    public QishuiResolvedPlayback resolveDirect(String trackId, String quality) throws Exception {
        ParseResult parsed = parseWithRetry(trackId, quality);
        String directUrl = resolveRedirect(parsed.playUrl);
        return new QishuiResolvedPlayback(directUrl, parsed.playAuth);
    }

    /** Reads the 302 Location without downloading the body. */
    private String resolveRedirect(String playUrl) throws IOException {
        Request req = new Request.Builder().url(playUrl)
                .header("User-Agent", "tunefree-next-bff/1.0").get().build();
        OkHttpClient noFollow = http.newBuilder()
                .followRedirects(false).followSslRedirects(false).build();
        try (Response r = noFollow.newCall(req).execute()) {
            if ((r.code() == 301 || r.code() == 302) && r.header("Location") != null) {
                return r.header("Location");
            }
            return r.request().url().toString();
        }
    }

    /** Final upstream CDN URL (post-redirect) and the CENC play auth needed to decrypt it. */
    public static final class QishuiResolvedPlayback {
        public final String directUrl;
        public final String playAuth;

        QishuiResolvedPlayback(String directUrl, String playAuth) {
            this.directUrl = directUrl;
            this.playAuth = playAuth;
        }
    }

    private ParseResult parseWithRetry(String trackId, String quality) {
        try {
            return parse(trackId, quality, auth.getToken());
        } catch (MusicBusinessException first) {
            // A revoked/expired token is the common cause: force one clean re-login.
            log.info("Qishui parse failed, retrying once with a fresh v1 token: {}", first.getMessage());
            auth.invalidate();
            return parse(trackId, quality, auth.getToken());
        }
    }

    private ParseResult parse(String trackId, String quality, String token) {
        JsonNode body = mapper.createObjectNode()
                .put("source", "qs")
                .put("trackId", trackId)
                .put("quality", quality);
        Request req = new Request.Builder()
                .url(PARSE_URL)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        JsonNode root;
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (resp.code() == 401 || resp.code() == 403) {
                auth.invalidate();
                throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                        "TuneFree v1 rejected the access token (HTTP " + resp.code() + ")");
            }
            if (resp.code() < 200 || resp.code() >= 300) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "TuneFree v1 parse returned HTTP " + resp.code());
            }
            root = mapper.readTree(raw);
        } catch (InterruptedIOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "TuneFree v1 parse timed out", e);
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse I/O failed: " + e.getMessage(), e);
        }
        if (root == null || !root.path("success").asBoolean(false)) {
            String code = root == null ? "?" : root.path("error").path("code").asText("?");
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse rejected: " + code);
        }
        JsonNode data = root.path("data");
        String playUrl = data.path("playUrl").asText("");
        JsonNode encryption = data.path("encryption");
        String playAuth = null;
        if (encryption.isObject()) {
            for (String field : new String[] {"spadeA", "spade_a", "SpadeA", "playAuth", "play_auth"}) {
                if (encryption.path(field).isTextual() && !encryption.path(field).asText("").isEmpty()) {
                    playAuth = encryption.path(field).asText();
                    break;
                }
            }
        }
        if (playUrl.isEmpty() || playAuth == null) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse response missing playUrl or play auth");
        }
        if (!playUrl.startsWith("https://") && !playUrl.startsWith("http://")) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 returned an invalid play URL");
        }
        return new ParseResult(playUrl, playAuth);
    }

    private byte[] download(String url) {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            if (resp.code() < 200 || resp.code() >= 300 || rb == null) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio download returned HTTP " + resp.code());
            }
            long declared = rb.contentLength();
            if (declared > MAX_AUDIO_BYTES) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio exceeds size limit");
            }
            byte[] data = rb.bytes();
            if (data.length > MAX_AUDIO_BYTES) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio exceeds size limit");
            }
            return data;
        } catch (InterruptedIOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "Qishui audio download timed out", e);
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "Qishui audio download failed: " + e.getMessage(), e);
        }
    }

    /**
     * A handle to decrypted audio. When {@code path} is set the bytes stay on disk and only the
     * requested Range is read into a small buffer; when the disk cache is unavailable the
     * {@code bytes} fallback preserves the original in-memory behaviour. A {@code task} means
     * the file is still being written and Range reads wait only for the bytes they need.
     */
    public static final class AudioFile {
        private static final int BUFFER_SIZE = 64 * 1024;
        private final Path path;
        private final byte[] bytes;
        private final long length;
        private final LoadTask task;

        private AudioFile(Path path, byte[] bytes, long length) {
            this(path, bytes, length, null);
        }

        private AudioFile(Path path, byte[] bytes, long length, LoadTask task) {
            this.path = path;
            this.bytes = bytes;
            this.length = length;
            this.task = task;
        }

        public long length() {
            return length;
        }

        boolean isValid() {
            if (task != null) {
                Path p = task.currentPath();
                return p != null && Files.isRegularFile(p);
            }
            return path == null ? bytes != null : Files.isRegularFile(path);
        }

        /** Writes the inclusive byte range [start, end] to the response stream. */
        public void writeRange(OutputStream out, long start, long end) throws IOException {
            long remaining = end - start + 1;
            if (remaining <= 0) {
                return;
            }
            if (bytes != null && task == null) {
                out.write(bytes, (int) start, (int) remaining);
                return;
            }
            Path source = task == null ? path : task.currentPath();
            if (source == null) {
                throw new IOException("Qishui audio file unavailable");
            }
            if (task != null) {
                task.awaitReady(end + 1);
                source = task.currentPath();
                if (source == null) {
                    throw new IOException("Qishui audio file unavailable");
                }
            }
            try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                long position = start;
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer, position);
                    if (read < 0) {
                        throw new EOFException("Qishui audio cache truncated at " + position);
                    }
                    out.write(buffer.array(), 0, read);
                    position += read;
                    remaining -= read;
                }
            }
        }
    }

    private static final class ParseResult {
        final String playUrl;
        final String playAuth;

        ParseResult(String playUrl, String playAuth) {
            this.playUrl = playUrl;
            this.playAuth = playAuth;
        }
    }
}
