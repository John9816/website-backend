package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.CurrentUserView;
import com.example.website.dto.UserProfileUpdateRequest;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024L * 1024L;
    private static final Path AVATAR_DIR = Paths.get("uploads", "avatars");
    private static final String AVATAR_URL_PREFIX = "/api/v1/user/avatar/";

    private final UserRepository userRepository;
    private final AuthUserCacheService authUserCacheService;

    @Transactional(readOnly = true)
    public CurrentUserView getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        return CurrentUserView.from(user);
    }

    @Transactional
    public CurrentUserView updateProfile(Long userId, UserProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        String username = normalizeUsername(request.getUsername());
        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByUsernameAndIdNot(username, userId)) {
            throw new BusinessException(409, "Username already exists");
        }
        if (userRepository.existsByEmailAndIdNot(email, userId)) {
            throw new BusinessException(409, "QQ email already exists");
        }

        user.setUsername(username);
        user.setEmail(email);
        User saved = userRepository.save(user);
        authUserCacheService.evict(saved.getId());
        return CurrentUserView.from(saved);
    }

    @Transactional
    public CurrentUserView updateAvatar(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        String filename = storeAvatar(userId, file);

        String previousUrl = user.getAvatarUrl();
        user.setAvatarUrl(AVATAR_URL_PREFIX + filename);
        User saved = userRepository.save(user);
        deletePreviousAvatar(previousUrl, filename);
        authUserCacheService.evict(saved.getId());
        return CurrentUserView.from(saved);
    }

    public AvatarFile readAvatar(String filename) {
        if (!isSafeFilename(filename)) {
            throw new BusinessException(400, "Invalid filename");
        }
        Path base = AVATAR_DIR.toAbsolutePath().normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || !Files.exists(file)) {
            throw new BusinessException(404, "Avatar not found");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            MediaType mediaType = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
            return new AvatarFile(bytes, mediaType);
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to read avatar");
        }
    }

    private String storeAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "avatar is required");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(413, "avatar is too large");
        }
        String contentType = normalizeContentType(file.getContentType());
        String extension = extensionFor(contentType);
        if (extension == null) {
            throw new BusinessException(400, "Only jpg, png, webp and gif avatars are allowed");
        }

        String filename = userId + "-" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path base = AVATAR_DIR.toAbsolutePath().normalize();
        Path target = base.resolve(filename).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException(400, "Invalid avatar filename");
        }
        try {
            Files.createDirectories(base);
            file.transferTo(target);
            return filename;
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to store avatar");
        }
    }

    private void deletePreviousAvatar(String previousUrl, String currentFilename) {
        if (!StringUtils.hasText(previousUrl) || !previousUrl.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        String previousFilename = previousUrl.substring(AVATAR_URL_PREFIX.length());
        if (!isSafeFilename(previousFilename) || previousFilename.equals(currentFilename)) {
            return;
        }
        try {
            Path base = AVATAR_DIR.toAbsolutePath().normalize();
            Path file = base.resolve(previousFilename).normalize();
            if (file.startsWith(base)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(400, "username is required");
        }
        return username.trim();
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "QQ email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String extensionFor(String contentType) {
        switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE:
                return "jpg";
            case MediaType.IMAGE_PNG_VALUE:
                return "png";
            case "image/webp":
                return "webp";
            case MediaType.IMAGE_GIF_VALUE:
                return "gif";
            default:
                return null;
        }
    }

    private boolean isSafeFilename(String filename) {
        return filename != null && filename.matches("^[A-Za-z0-9._-]+$");
    }

    public static class AvatarFile {
        private final byte[] bytes;
        private final MediaType mediaType;

        AvatarFile(byte[] bytes, MediaType mediaType) {
            this.bytes = bytes;
            this.mediaType = mediaType;
        }

        public ByteArrayResource resource() {
            return new ByteArrayResource(bytes);
        }

        public MediaType mediaType() {
            return mediaType;
        }
    }
}
