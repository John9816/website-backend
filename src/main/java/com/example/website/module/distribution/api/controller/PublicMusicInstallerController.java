package com.example.website.module.distribution.api.controller;

import com.example.website.common.ApiResponse;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public installer metadata for the music promo page.
 *
 * <p>Serves the same contract as the static Pages/ECS handlers so the SPA can call the Java
 * backend directly in local and reverse-proxy deployments.
 */
@RestController
@RequestMapping("/api/public/music/installer")
public class PublicMusicInstallerController {

    private static final String DOWNLOAD_URL = "/downloads/oldwang-music-installer.zip";
    private static final String FILE_NAME = "oldwang-music-installer.zip";

    @GetMapping("/latest")
    public ApiResponse<Map<String, Object>> latest() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", "1.0.0");
        data.put("title", "oldwang Music Installer");
        data.put("fileName", FILE_NAME);
        data.put("downloadUrl", DOWNLOAD_URL);
        data.put("sizeBytes", resolvePackageSize());
        data.put("releasedAt", LocalDate.now().toString());
        data.put("notes", "Latest public installer package for the music app.");
        return ApiResponse.ok(data);
    }

    private long resolvePackageSize() {
        try {
            Path local = Paths.get("uploads", "downloads", FILE_NAME);
            if (Files.isRegularFile(local)) {
                return Files.size(local);
            }
            Resource resource = new ClassPathResource("public/downloads/" + FILE_NAME);
            if (resource.exists()) {
                return resource.contentLength();
            }
        } catch (IOException ignored) {
            // fall through to a conservative default for missing local packages
        }
        return 323L;
    }

    @Data
    public static class MusicInstallerReleaseView {
        private String version;
        private String title;
        private String fileName;
        private String downloadUrl;
        private Long sizeBytes;
        private String releasedAt;
        private String notes;
    }
}
