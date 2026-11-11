package com.example.website.module.music.application;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Bounded on-disk cache for decrypted Qishui audio.
 *
 * <p>The API runs with a 768 MB heap on a 2C2G host, so the in-memory Caffeine cache is
 * deliberately kept small (64 MB). A lossless track is ~33 MB; persisting plaintext to disk keeps
 * "play it again" and seek fast without risking an OOM kill, and the entries survive a restart.
 *
 * <p>Every operation degrades to a no-op on I/O failure: disk caching is an optimisation, never a
 * prerequisite for playback. Keys are hashed before becoming filenames, so a track id can never
 * influence the path.
 */
@Slf4j
final class QishuiDiskCache {

    private final Path dir;
    private final long maxBytes;
    private final long ttlMillis;
    private final boolean enabled;

    QishuiDiskCache(String dirPath, long maxBytes, long ttlSeconds) {
        this.maxBytes = maxBytes;
        this.ttlMillis = ttlSeconds * 1000L;
        Path resolved = null;
        boolean ok = false;
        try {
            resolved = Paths.get(dirPath);
            Files.createDirectories(resolved);
            ok = Files.isDirectory(resolved) && Files.isWritable(resolved);
            if (!ok) {
                log.warn("Qishui disk cache path is not writable: {}", dirPath);
            }
        } catch (Exception e) {
            log.warn("Qishui disk cache disabled ({}): {}", dirPath, e.toString());
        }
        this.dir = resolved;
        this.enabled = ok;
        if (enabled) {
            log.info("Qishui disk cache enabled dir={} maxBytes={} ttlSec={}",
                    dirPath, maxBytes, ttlSeconds);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a live, already-decrypted cache file for the key without loading its bytes into
     * the heap, or null on miss / expiry / I/O error. The stream endpoint uses this to serve
     * HTTP Range requests with positional reads instead of materialising the whole track.
     */
    Path resolve(String key) {
        if (!enabled) {
            return null;
        }
        Path f = file(key);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
            if (age > ttlMillis) {
                Files.deleteIfExists(f);
                return null;
            }
            long size = Files.size(f);
            return size == 0 ? null : f;
        } catch (IOException e) {
            log.warn("Qishui disk cache resolve failed key={}: {}", key, e.toString());
            return null;
        }
    }

    /** Returns cached bytes, or null on miss / expiry / I/O error. */
    byte[] read(String key) {
        if (!enabled) {
            return null;
        }
        Path f = file(key);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
            if (age > ttlMillis) {
                Files.deleteIfExists(f);
                return null;
            }
            byte[] data = Files.readAllBytes(f);
            return data.length == 0 ? null : data;
        } catch (IOException e) {
            log.warn("Qishui disk cache read failed key={}: {}", key, e.toString());
            return null;
        }
    }

    /** Atomically publishes a cache entry, then enforces the TTL and size bounds. */
    void write(String key, byte[] data) {
        if (!enabled || data == null || data.length == 0) {
            return;
        }
        Path f = file(key);
        Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, data, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            trim();
        } catch (IOException e) {
            log.warn("Qishui disk cache write failed key={}: {}", key, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private Path file(String key) {
        return dir.resolve(sha256Hex(key) + ".bin");
    }

    /** Staging path for an in-progress streaming load. Excluded from trim() (no .bin suffix). */
    Path staging(String key) {
        return enabled ? file(key).resolveSibling(file(key).getFileName() + ".part") : null;
    }

    /** Final cache path, only published once the whole file is written. */
    Path target(String key) {
        return enabled ? file(key) : null;
    }

    /**
     * Atomically publishes a completed staging file. Returns false when the disk cache is
     * disabled or the move failed; the caller then simply keeps serving from staging.
     */
    boolean publish(String key, Path staging) {
        if (!enabled || staging == null) return false;
        Path f = file(key);
        try {
            try {
                Files.move(staging, f, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, f, StandardCopyOption.REPLACE_EXISTING);
            }
            trim();
            return true;
        } catch (IOException e) {
            log.warn("Qishui disk cache publish failed key={}: {}", key, e.toString());
            return false;
        }
    }

    /** Deletes expired entries first, then evicts oldest-first until the size bound is met. */
    private synchronized void trim() {
        if (!enabled) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = new ArrayList<>();
            long now = System.currentTimeMillis();
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (!p.getFileName().toString().endsWith(".bin")) {
                    continue;
                }
                try {
                    if (now - Files.getLastModifiedTime(p).toMillis() > ttlMillis) {
                        Files.deleteIfExists(p);
                    } else {
                        entries.add(p);
                    }
                } catch (IOException ignored) {
                    // skip an unreadable entry
                }
            }
            entries.sort(Comparator.comparingLong(this::modifiedTime));
            long total = 0;
            for (Path p : entries) {
                total += size(p);
            }
            for (int i = 0; i < entries.size() && total > maxBytes; i++) {
                Path p = entries.get(i);
                long sz = size(p);
                try {
                    Files.deleteIfExists(p);
                    total -= sz;
                } catch (IOException ignored) {
                    // skip
                }
            }
        } catch (IOException e) {
            log.warn("Qishui disk cache trim failed: {}", e.toString());
        }
    }

    private long modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sha256Hex(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for every JDK; this branch is unreachable in practice.
            return Integer.toHexString(key.hashCode());
        }
    }
}
