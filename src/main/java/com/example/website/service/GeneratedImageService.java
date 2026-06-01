package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.GeneratedImageView;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.dto.PageView;
import com.example.website.entity.GeneratedImage;
import com.example.website.repository.GeneratedImageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeneratedImageService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DAILY_GENERATION_LIMIT = 100;
    private static final Pattern BASE64_DATA_PATTERN =
            Pattern.compile("data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)");
    private static final String SERVE_PATH_PREFIX = "/api/v1/image/file/";

    private final GeneratedImageRepository repository;
    private final SysConfigService sysConfigService;
    private final GoFileClient goFileClient;
    private final TransactionTemplate transactionTemplate;

    public GeneratedImageService(GeneratedImageRepository repository,
                                 SysConfigService sysConfigService,
                                 GoFileClient goFileClient,
                                 TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.sysConfigService = sysConfigService;
        this.goFileClient = goFileClient;
        this.transactionTemplate = transactionTemplate;
    }

    public void checkDailyLimit(Long userId) {
        long todayCount = repository.countByUserIdSince(userId, LocalDate.now().atStartOfDay());
        if (todayCount >= DAILY_GENERATION_LIMIT) {
            throw new BusinessException(429, "今日生图次数已达上限（" + DAILY_GENERATION_LIMIT + " 张），请明天再试");
        }
    }

    public void saveBatch(Long userId, String prompt, String size, ImageGenerationsResponse resp) {
        if (userId == null || resp == null || resp.getData() == null || resp.getData().isEmpty()) {
            return;
        }
        // Resolve image references outside any transaction so HikariCP connections
        // are not held during multi-second HTTP calls or filesystem writes.
        List<GeneratedImage> toPersist = new ArrayList<>();
        for (ImageGenerationsResponse.ImageDataItem item : resp.getData()) {
            if (item.getUrl() == null || item.getUrl().isEmpty()) continue;
            String resolved = resolveImageUrl(item.getUrl());
            if (resolved == null) continue;
            GeneratedImage e = new GeneratedImage();
            e.setUserId(userId);
            e.setPrompt(prompt);
            e.setModel(resp.getModel());
            e.setSize(size);
            e.setImageUrl(resolved);
            toPersist.add(e);
        }
        if (toPersist.isEmpty()) {
            return;
        }
        // Now open a short transaction just for the inserts.
        transactionTemplate.execute(status -> {
            repository.saveAll(toPersist);
            return null;
        });
    }

    private String resolveImageUrl(String rawUrl) {
        if (isRemoteHttpUrl(rawUrl) && useDirectRemoteUrlMode()) {
            return rawUrl;
        }

        // Extract bytes from whatever source: base64 data URL or remote HTTP URL
        ImageBytes img = extractBytes(rawUrl);
        if (img == null) {
            log.warn("Cannot extract image bytes from {}", rawUrl.substring(0, Math.min(80, rawUrl.length())));
            return null;
        }

        // Try go-file first
        String goFileUrl = sysConfigService.getValue(ImageService.CFG_GOFILE_URL).orElse(null);
        if (goFileUrl != null && !goFileUrl.trim().isEmpty()) {
            String goFileToken = sysConfigService.getValue(ImageService.CFG_GOFILE_TOKEN).orElse(null);
            Optional<String> publicUrl = goFileClient.upload(goFileUrl, goFileToken, img.bytes, img.filename);
            if (publicUrl.isPresent()) {
                return publicUrl.get();
            }
            log.warn("go-file upload failed, falling back to local disk");
        }

        // Fallback to local filesystem
        String filename = saveToFile(img.bytes, img.ext);
        if (filename != null) {
            return SERVE_PATH_PREFIX + filename;
        }
        log.warn("Failed to persist image, skipping");
        return null;
    }

    private ImageBytes extractBytes(String rawUrl) {
        // Case 1: base64 data URL
        Matcher b64 = BASE64_DATA_PATTERN.matcher(rawUrl);
        if (b64.find()) {
            String ext = b64.group(1).equalsIgnoreCase("png") ? "png" : b64.group(1);
            byte[] bytes = Base64.getDecoder().decode(b64.group(2));
            return new ImageBytes(bytes, ext, UUID.randomUUID().toString().replace("-", "") + "." + ext);
        }

        // Case 2: remote HTTP URL — download it
        if (isRemoteHttpUrl(rawUrl)) {
            byte[] bytes = goFileClient.downloadBytes(rawUrl);
            if (bytes != null) {
                String ext = inferExt(rawUrl, null);
                return new ImageBytes(bytes, ext, UUID.randomUUID().toString().replace("-", "") + "." + ext);
            }
            return null;
        }

        return null;
    }

    private boolean useDirectRemoteUrlMode() {
        return sysConfigService.getValue(ImageService.CFG_REMOTE_URL_MODE)
                .map(String::trim)
                .map("direct"::equalsIgnoreCase)
                .orElse(true);
    }

    private boolean isRemoteHttpUrl(String rawUrl) {
        return rawUrl != null && (rawUrl.startsWith("http://") || rawUrl.startsWith("https://"));
    }

    private String inferExt(String url, String contentType) {
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "png";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "jpg";
        if (lower.contains(".webp")) return "webp";
        if (lower.contains(".gif")) return "gif";
        if (contentType != null) {
            if (contentType.contains("png")) return "png";
            if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
            if (contentType.contains("webp")) return "webp";
        }
        return "png";
    }

    private static class ImageBytes {
        final byte[] bytes;
        final String ext;
        final String filename;

        ImageBytes(byte[] bytes, String ext, String filename) {
            this.bytes = bytes;
            this.ext = ext;
            this.filename = filename;
        }
    }

    private String saveToFile(byte[] data, String ext) {
        String uploadDir = sysConfigService.getValue(ImageService.CFG_UPLOAD_DIR).orElse(null);
        if (uploadDir == null || uploadDir.trim().isEmpty()) {
            uploadDir = "uploads/images";
        }
        try {
            Path dir = Paths.get(uploadDir.trim());
            Files.createDirectories(dir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path file = dir.resolve(filename);
            try (OutputStream out = Files.newOutputStream(file)) {
                out.write(data);
            }
            return filename;
        } catch (IOException e) {
            log.error("Failed to write image file to {}: {}", uploadDir, e.getMessage());
            return null;
        }
    }

    public byte[] readFile(String filename) {
        String uploadDir = sysConfigService.getValue(ImageService.CFG_UPLOAD_DIR).orElse(null);
        if (uploadDir == null || uploadDir.trim().isEmpty()) {
            uploadDir = "uploads/images";
        }
        Path file = Paths.get(uploadDir.trim()).resolve(filename).normalize();
        if (!file.startsWith(Paths.get(uploadDir.trim()).normalize())) {
            throw new BusinessException(400, "Invalid filename");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException(404, "Image file not found");
        }
    }

    public PageView<GeneratedImageView> list(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<GeneratedImageRepository.GeneratedImageSummary> result =
                repository.findSummariesByUserId(userId, pageable);
        return PageView.from(result, GeneratedImageView::from);
    }

    @Transactional
    public GeneratedImageView toggleShare(Long userId, Long id, boolean share) {
        GeneratedImage e = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Image history not found"));
        e.setIsShared(share);
        return GeneratedImageView.from(repository.save(e));
    }

    public PageView<GeneratedImageView> listShared(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<GeneratedImageRepository.GeneratedImageSummary> result =
                repository.findSharedSummaries(pageable);
        return PageView.from(result, GeneratedImageView::from);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        GeneratedImage e = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Image history not found"));
        repository.delete(e);
    }
}
