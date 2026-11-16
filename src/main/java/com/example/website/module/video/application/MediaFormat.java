package com.example.website.module.video.application;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Container hints handed to clients together with a playable URL.
 *
 * <p>The player picks a decoding path from this value, so it must never guess: an
 * unrecognised extension stays {@link #UNKNOWN} rather than being reported as HLS.
 */
public final class MediaFormat {

    /** Extension could not be determined; the player may still try the stream. */
    public static final String UNKNOWN = "unknown";

    /** Supported extensions, kept as one source of truth for both URL matching and lookups. */
    public static final String EXTENSION_PATTERN =
            "m3u8|mp4|mpd|mkv|webm|ts|m2ts|mts|flv|avi|mov|m4v|f4v|3gp|3gpp|wmv|asf|rmvb|rm|vob|ogv";

    private static final Set<String> MEDIA_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(EXTENSION_PATTERN.split("\\|"))));

    private MediaFormat() {
    }

    /**
     * Returns the container hint for {@code media}, or {@link #UNKNOWN} when the extension is
     * missing or not recognised.
     */
    public static String infer(String media) {
        String extension = extension(media);
        if (extension.isEmpty()) return UNKNOWN;
        switch (extension) {
            case "mp4":
            case "m4v":
            case "f4v":
            case "mov":
                return "mp4";
            case "m3u8":
                return "hls";
            case "mpd":
                return "dash";
            case "m2ts":
            case "mts":
                return "ts";
            case "3gpp":
                return "3gp";
            default:
                return MEDIA_EXTENSIONS.contains(extension) ? extension : UNKNOWN;
        }
    }

    /** True when {@code url} carries a recognised media extension; query and fragment are ignored. */
    public static boolean isMediaUrl(String url) {
        return MEDIA_EXTENSIONS.contains(extension(url));
    }

    private static String extension(String media) {
        if (media == null) return "";
        String value = media.trim();
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
