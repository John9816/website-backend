package com.example.website.module.images.application;

import com.example.website.module.assets.application.ImageStorageService;

import com.example.website.common.BusinessException;
import com.example.website.module.images.api.dto.GeneratedImageView;
import com.example.website.module.images.api.dto.ImageGenerationsResponse;
import com.example.website.shared.dto.PageView;
import com.example.website.module.images.domain.GeneratedImage;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.images.infrastructure.persistence.GeneratedImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class GeneratedImageService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String TYPE_IMAGE = "image";

    private final GeneratedImageRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final String sharedStorageDir;
    private final String configuredImageUploadDir;
    private final ImageStorageService imageStorageService;

    public GeneratedImageService(GeneratedImageRepository repository,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${app.storage.shared-dir:}") String sharedStorageDir,
                                 @Value("${app.storage.image-dir:uploads/images}") String configuredImageUploadDir,
                                 ImageStorageService imageStorageService) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.sharedStorageDir = sharedStorageDir;
        this.configuredImageUploadDir = configuredImageUploadDir;
        this.imageStorageService = imageStorageService;
    }


    public ImageGenerationsResponse saveBatch(Long userId, String prompt, String size,
                                              ImageGenerationsResponse resp) {
        if (userId == null || resp == null || resp.getData() == null || resp.getData().isEmpty()) {
            return resp;
        }
        // Resolve every item before mutating the response or history. A task may only
        // become successful when every result has a durable public URL.
        List<GeneratedImage> toPersist = new ArrayList<>();
        List<String> persistedUrls = new ArrayList<>();
        for (ImageGenerationsResponse.ImageDataItem item : resp.getData()) {
            String reference = imageReference(item);
            if (reference == null) {
                throw new BusinessException(502, "Image upstream returned an empty image reference");
            }
            String resolved = resolveImageUrl(reference);
            persistedUrls.add(resolved);
            GeneratedImage e = new GeneratedImage();
            e.setUserId(userId);
            e.setType(TYPE_IMAGE);
            e.setPrompt(prompt);
            e.setModel(resp.getModel());
            e.setSize(size);
            e.setImageUrl(resolved);
            toPersist.add(e);
        }
        for (int index = 0; index < resp.getData().size(); index++) {
            ImageGenerationsResponse.ImageDataItem item = resp.getData().get(index);
            item.setUrl(persistedUrls.get(index));
            item.setB64Json(null);
        }
        // Now open a short transaction just for the inserts.
        transactionTemplate.execute(status -> {
            repository.saveAll(toPersist);
            return null;
        });
        return resp;
    }

    private String resolveImageUrl(String rawUrl) {
        ImageAsset asset = imageStorageService.storeReference(rawUrl);
        String persisted = asset == null ? null : asset.getImageUrl();
        if (persisted == null || persisted.trim().isEmpty()) {
            throw new BusinessException(502, "Image storage returned no public URL");
        }
        return persisted.trim();
    }

    public String persistImageReference(String rawUrl) {
        return resolveImageUrl(rawUrl);
    }

    /** Uploads an in-memory image and returns its durable public URL. */
    public String persistImageBytes(byte[] bytes, String contentType) {
        ImageAsset asset = imageStorageService.storeBytes(bytes, contentType);
        if (asset == null || asset.getImageUrl() == null || asset.getImageUrl().trim().isEmpty()) {
            throw new BusinessException(502, "Image storage returned no public URL");
        }
        return asset.getImageUrl().trim();
    }

    private String imageReference(ImageGenerationsResponse.ImageDataItem item) {
        if (item.getUrl() != null && !item.getUrl().trim().isEmpty()) {
            return item.getUrl();
        }
        if (item.getB64Json() != null && !item.getB64Json().trim().isEmpty()) {
            return "data:image/png;base64," + item.getB64Json().trim();
        }
        return null;
    }

    public byte[] readFile(String filename) {
        Path uploadDir = imageUploadDir();
        Path file = uploadDir.resolve(filename).normalize();
        if (!file.startsWith(uploadDir)) {
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

    private Path imageUploadDir() {
        String uploadDir = System.getProperty("website.image.upload-dir", configuredImageUploadDir);
        if (uploadDir == null || uploadDir.trim().isEmpty()) {
            uploadDir = "uploads/images";
        }
        Path resolved = Paths.get(uploadDir.trim());
        if (!resolved.isAbsolute() && sharedStorageDir != null && !sharedStorageDir.trim().isEmpty()) {
            resolved = Paths.get(sharedStorageDir.trim()).resolve(resolved);
        }
        return resolved.toAbsolutePath().normalize();
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
