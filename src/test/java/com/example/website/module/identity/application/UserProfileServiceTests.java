package com.example.website.module.identity.application;

import com.example.website.module.assets.application.ImageStorageService;

import com.example.website.module.identity.api.dto.CurrentUserView;
import com.example.website.module.identity.domain.User;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.common.BusinessException;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTests {

    @Test
    void remoteAvatarWritesOnlyHostedUrlAndLeavesUserUnchangedOnUploadFailure() {
        User user = new User();
        user.setId(7L);
        user.setAvatarUrl("/api/v1/user/avatar/old.png");
        ImageStorageService storage = mock(ImageStorageService.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        UserProfileService service = new UserProfileService(userRepository, authUserCacheService,
                tempDir.toString(), storage, true);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1});
        when(storage.storeBytes(any(), any())).thenThrow(new BusinessException(502, "Image upload failed"));
        assertThrows(BusinessException.class, () -> service.updateAvatar(7L, file));
        verify(userRepository, never()).updateAvatarUrl(any(), any());
        assertEquals("/api/v1/user/avatar/old.png", user.getAvatarUrl());

        ImageAsset asset = new ImageAsset();
        asset.setImageUrl("https://i.ibb.co/test/avatar.png");
        doReturn(asset).when(storage).storeBytes(any(), any());
        when(userRepository.updateAvatarUrl(7L, asset.getImageUrl())).thenAnswer(invocation -> {
            user.setAvatarUrl(asset.getImageUrl());
            return 1;
        });
        assertEquals(asset.getImageUrl(), service.updateAvatar(7L, file).getAvatarUrl());
        verify(userRepository, never()).save(any());
        verify(authUserCacheService).evict(7L);
    }

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthUserCacheService authUserCacheService;

    @TempDir
    Path tempDir;

    @Test
    void avatarRemainsReadableAcrossInstancesUsingConfiguredDirectory() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        User user = new User();
        user.setId(7L);
        user.setUsername("avatar-user");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Path sharedAvatarDir = tempDir.resolve("shared/uploads/avatars");
        UserProfileService uploadInstance = new UserProfileService(
                userRepository,
                authUserCacheService,
                sharedAvatarDir.toString());
        CurrentUserView updated = uploadInstance.updateAvatar(
                7L,
                new MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, png));

        String filename = updated.getAvatarUrl().substring("/api/v1/user/avatar/".length());
        assertTrue(Files.isRegularFile(sharedAvatarDir.resolve(filename)));

        UserProfileService restartedInstance = new UserProfileService(
                userRepository,
                authUserCacheService,
                sharedAvatarDir.toString());
        assertArrayEquals(
                png,
                restartedInstance.readAvatar(filename).resource().getByteArray());
    }
}
