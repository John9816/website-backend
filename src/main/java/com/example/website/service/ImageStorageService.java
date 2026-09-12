package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.example.website.entity.ImageAsset;
import com.example.website.repository.ImageAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImageStorageService {

    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private final ImageAssetRepository repository;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String uploadUrl;

    public ImageStorageService(ImageAssetRepository repository,
                               @Qualifier(OkHttpConfig.CLIENT_IMAGE_UPSTREAM) OkHttpClient httpClient,
                               ObjectMapper objectMapper,
                               @Value("${app.storage.image-upload-url:https://api.xunhuisi.store/API/Tuchuang/Tc.php}") String uploadUrl) {
        this.repository = repository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.uploadUrl = uploadUrl;
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
        if (!isHttpUrl(uploadUrl)) {
            throw new BusinessException(500, "Invalid image upload endpoint");
        }
        // The provider's live form uses photo/type, despite the catalog listing image.
        FormBody body = new FormBody.Builder().add("photo", photo).add("type", "json").build();
        Request request = new Request.Builder().url(uploadUrl).post(body).build();
        ImageAsset asset = new ImageAsset();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502, "Image upload failed: HTTP " + response.code());
            }
            String raw = response.body().string();
            JsonNode json = objectMapper.readTree(raw);
            JsonNode code = json == null ? null : json.get("code");
            String url = json == null ? "" : json.path("purl").asText("").trim();
            if (code == null || !code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0
                    || !isHttpUrl(url) || url.length() > 1000) {
                throw new BusinessException(502, "Image upload returned an invalid result");
            }
            asset.setImageUrl(url);
            asset.setUrlHash(hash(url));
            asset.setHost(json.path("host").asText(null));
            asset.setDeleteUrl(json.path("delete_url").asText(null));
            asset.setSource(json.path("source").asText(null));
            asset.setResponseJson(raw);
            asset.setContentType(contentType);
            asset.setByteSize(byteSize);
        } catch (IOException e) {
            throw new BusinessException(502, "Image upload failed or returned invalid JSON");
        }
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
