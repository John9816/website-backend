package com.example.website.controller;

import com.example.website.common.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@RestController
public class ContentAssetController {

    private static final Path CONTENT_ASSET_DIR = Paths.get("uploads", "content-assets");

    @GetMapping("/api/v1/content/assets/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) throws IOException {
        if (!filename.matches("^[A-Za-z0-9._-]+$")) {
            throw new BusinessException(400, "Invalid filename");
        }
        Path path = CONTENT_ASSET_DIR.resolve(filename).normalize();
        if (!path.startsWith(CONTENT_ASSET_DIR) || !Files.exists(path)) {
            throw new BusinessException(404, "File not found");
        }
        byte[] data = Files.readAllBytes(path);
        MediaType mediaType = MediaTypeFactory.getMediaType(filename)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length))
                .body(data);
    }
}
