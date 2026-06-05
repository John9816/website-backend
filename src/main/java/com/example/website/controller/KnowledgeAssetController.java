package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.common.BusinessException;
import com.example.website.dto.kb.KbAssetUploadView;
import com.example.website.service.UserScopeService;
import com.example.website.service.kb.KbDocService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
public class KnowledgeAssetController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final Path ASSET_DIR = Paths.get("uploads", "kb-assets");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> EXTENSIONS;

    static {
        Map<String, String> values = new HashMap<>();
        values.put("image/avif", "avif");
        values.put("image/gif", "gif");
        values.put("image/jpeg", "jpg");
        values.put("image/png", "png");
        values.put("image/svg+xml", "svg");
        values.put("image/webp", "webp");
        EXTENSIONS = Collections.unmodifiableMap(values);
    }

    private final UserScopeService userScopeService;
    private final KbDocService docService;

    @PostMapping({"/api/user/kb/assets", "/api/admin/kb/assets"})
    public ApiResponse<KbAssetUploadView> upload(HttpServletRequest request,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "docId", required = false) Long docId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        if (docId != null) {
            docService.requireOwned(userId, docId);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "file is required");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(413, "file is too large");
        }

        String contentType = normalizeContentType(file.getContentType());
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(400, "Only image files are allowed");
        }

        String filename = userId + "-" + (docId == null ? "unassigned" : docId) + "-" + randomToken() + "." + extension;
        Path target = ASSET_DIR.resolve(filename).normalize();
        try {
            Files.createDirectories(ASSET_DIR);
            file.transferTo(target);
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to store file");
        }

        return ApiResponse.ok(KbAssetUploadView.builder()
                .url("/api/v1/kb/assets/" + filename)
                .key("kb-assets/" + filename)
                .contentType(contentType)
                .size(file.getSize())
                .build());
    }

    @GetMapping("/api/v1/kb/assets/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) throws IOException {
        if (!filename.matches("^[A-Za-z0-9._-]+$")) {
            throw new BusinessException(400, "Invalid filename");
        }
        Path path = ASSET_DIR.resolve(filename).normalize();
        if (!path.startsWith(ASSET_DIR) || !Files.exists(path)) {
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

    private static String normalizeContentType(String value) {
        String contentType = StringUtils.hasText(value) ? value.toLowerCase().split(";")[0].trim() : "";
        return "image/jpg".equals(contentType) ? "image/jpeg" : contentType;
    }

    private static String randomToken() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
