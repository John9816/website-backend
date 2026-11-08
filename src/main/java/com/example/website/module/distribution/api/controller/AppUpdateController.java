package com.example.website.module.distribution.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.config.AppProperties;
import com.example.website.module.distribution.api.dto.AppUpdateView;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequiredArgsConstructor
public class AppUpdateController {

    private static final String MANIFEST_NAME = "latest.json";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /** Raw JSON is intentional: the Android client also supports GitHub's raw release format. */
    @GetMapping(value = "/updates/latest.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> latestManifest() {
        Path manifest = updateDirectory().resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache().noStore().mustRevalidate())
                .body(new FileSystemResource(manifest));
    }

    @GetMapping(value = "/updates/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
        Path directory = updateDirectory();
        Path file = directory.resolve(filename).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = filename.toLowerCase().endsWith(".apk")
                ? "application/vnd.android.package-archive"
                : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(file));
    }

    @GetMapping(value = "/api/public/app/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AppUpdateView>> updateInfo() throws IOException {
        Path manifest = updateDirectory().resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return ResponseEntity.notFound().build();
        }
        AppUpdateView view = objectMapper.readValue(manifest.toFile(), AppUpdateView.class);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().noStore().mustRevalidate())
                .body(ApiResponse.ok(view));
    }

    private Path updateDirectory() {
        return Paths.get(appProperties.getUpdate().getStorageDir()).toAbsolutePath().normalize();
    }
}
