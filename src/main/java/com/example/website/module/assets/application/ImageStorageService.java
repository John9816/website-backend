package com.example.website.module.assets.application;

import com.example.website.common.BusinessException;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.assets.infrastructure.persistence.ImageAssetRepository;
import okhttp3.HttpUrl;
import okio.ByteString;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Optional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImageStorageService {

    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private final ImageAssetRepository repository;
    private final R2ImageClient r2ImageClient;

    public ImageStorageService(ImageAssetRepository repository,
                               R2ImageClient r2ImageClient) {
        this.repository = repository;
        this.r2ImageClient = r2ImageClient;
    }

    public ImageAsset storeReference(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new BusinessException(400, "Image reference is required");
        }
        String value = reference.trim();
        if (value.startsWith("data:image/")) {
            int comma = value.indexOf(',');
            if (comma < 0 || !value.substring(0, comma).endsWith(";base64")) {
                throw new BusinessException(400, "Invalid image data URL");
            }
            if (value.length() - comma - 1 > ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
                throw new BusinessException(413, "Image is too large");
            }
            String contentType = value.substring(5, comma - ";base64".length());
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(value.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "Invalid image Base64");
            }
            return storeBytes(bytes, contentType);
        }
        if (!isHttpUrl(value)) {
            throw new BusinessException(400, "Image reference must be an HTTP URL or Base64 data URL");
        }
        Optional<ImageAsset> existing = repository.findFirstByUrlHash(hash(value));
        if (existing.isPresent() && value.equals(existing.get().getImageUrl())) {
            return existing.get();
        }
        return upload(value, null, null);
    }

    public ImageAsset storeBytes(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "Image is required");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(413, "Image is too large");
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType == null ? "" : contentType);
            if (!"image".equals(type.getType()) || type.isWildcardSubtype()) {
                throw new IllegalArgumentException("Not an image");
            }
            contentType = type.toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "Invalid image content type");
        }
        return upload(Base64.getEncoder().encodeToString(bytes), contentType, (long) bytes.length);
    }

    private ImageAsset upload(String photo, String contentType, Long byteSize) {
        // photo 有两种来源：storeBytes 传入的 base64（contentType 非空），
        // 或 storeReference 传入的远程 HTTP URL（contentType 为 null，需先下载再转存）。
        String url;
        if (contentType != null && !contentType.isBlank()) {
            url = r2ImageClient.upload(photo, contentType);
        } else {
            url = r2ImageClient.uploadFromUrl(photo);
        }
        ImageAsset asset = new ImageAsset();
        asset.setImageUrl(url);
        asset.setUrlHash(hash(url));
        asset.setHost("r2");
        asset.setSource("r2");
        asset.setContentType(contentType);
        asset.setByteSize(byteSize);
        // Repository.save opens a short transaction only after the upload has completed.
        return repository.save(asset);
    }

    private static boolean isHttpUrl(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value);
        return url != null && url.username().isEmpty() && url.password().isEmpty();
    }

    private static String hash(String value) {
        return ByteString.encodeUtf8(value).sha256().hex();
    }
}
