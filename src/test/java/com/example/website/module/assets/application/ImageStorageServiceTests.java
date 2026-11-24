package com.example.website.module.assets.application;

import com.example.website.common.BusinessException;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.assets.infrastructure.persistence.ImageAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImageStorageServiceTests {

    private static final String R2_URL = "https://km.751152.xyz/editor/media/images/abc123.png";
    private ImageAssetRepository repository;
    private R2ImageClient r2;
    private ImageStorageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ImageAssetRepository.class);
        when(repository.save(any(ImageAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        r2 = mock(R2ImageClient.class);
        when(r2.upload(anyString(), anyString())).thenReturn(R2_URL);
        when(r2.uploadFromUrl(anyString())).thenReturn(R2_URL);
        service = new ImageStorageService(repository, r2);
    }

    @Test
    void uploadsRemoteReferenceViaR2AndSavesMetadata() {
        ImageAsset asset = service.storeReference("https://upstream.example/image.png?token=a+b&size=2");

        verify(r2).uploadFromUrl("https://upstream.example/image.png?token=a+b&size=2");
        assertEquals(R2_URL, asset.getImageUrl());
        assertEquals("r2", asset.getHost());
        assertEquals("r2", asset.getSource());
        verify(repository).save(asset);
    }

    @Test
    void uploadsBytesAsBase64ViaR2AndRecordsMetadata() {
        ImageAsset asset = service.storeBytes("test".getBytes(StandardCharsets.UTF_8), "image/png");

        verify(r2).upload("dGVzdA==", "image/png");
        assertEquals(R2_URL, asset.getImageUrl());
        assertEquals("image/png", asset.getContentType());
        assertEquals(4L, asset.getByteSize());
    }

    @Test
    void acceptsDataUrlsAndStripsThePrefix() {
        ImageAsset asset = service.storeReference("data:image/webp;base64,dGVzdA==");

        verify(r2).upload("dGVzdA==", "image/webp");
        assertEquals("image/webp", asset.getContentType());
    }

    @Test
    void reusesAUrlAlreadyRecordedInTheDatabase() {
        ImageAsset existing = new ImageAsset();
        existing.setImageUrl(R2_URL);
        when(repository.findFirstByUrlHash(anyString())).thenReturn(Optional.of(existing));

        assertSame(existing, service.storeReference(R2_URL));
        verifyNoInteractions(r2);
        verify(repository, never()).save(any());
    }

    @Test
    void propagatesR2UploadFailureWithoutSaving() {
        when(r2.uploadFromUrl(anyString())).thenThrow(new BusinessException(502, "r2 down"));

        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.storeReference("https://upstream.example/image.png")).getCode());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/unknown/local.png", "file:///tmp/image.png", "data:image/png;base64,broken!"})
    void rejectsInvalidInputWithoutUploading(String reference) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.storeReference(reference)).getCode());
        verifyNoInteractions(r2);
        verify(repository, never()).save(any());
    }
}
