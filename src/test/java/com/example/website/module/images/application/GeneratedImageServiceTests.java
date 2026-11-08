package com.example.website.module.images.application;

import com.example.website.module.assets.application.ImageStorageService;

import com.example.website.module.images.api.dto.ImageGenerationsResponse;
import com.example.website.module.images.domain.GeneratedImage;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.images.infrastructure.persistence.GeneratedImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageServiceTests {

    @Mock
    private GeneratedImageRepository repository;

    @Mock
    private ImageStorageService imageStorageService;

    @TempDir
    Path tempDir;

    private GeneratedImageService generatedImageService;

    @BeforeEach
    void setUp() {
        TransactionTemplate inlineTxTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        generatedImageService = new GeneratedImageService(
                repository,
                inlineTxTemplate,
                tempDir.toString(),
                "uploads/images",
                imageStorageService);
    }

    @Test
    void saveBatchStoresRemoteUrlThroughImageStorage() {
        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        String upstreamUrl = "https://upstream.example.com/img.png";
        item.setUrl(upstreamUrl);
        ImageAsset stored = new ImageAsset();
        stored.setImageUrl("https://cdn.example.com/persisted.png");
        when(imageStorageService.storeReference(upstreamUrl)).thenReturn(stored);

        ImageGenerationsResponse resp = new ImageGenerationsResponse(1234567890L, "dall-e-3", Arrays.asList(item), null);
        generatedImageService.saveBatch(1L, "prompt", "1024x1024", resp);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeneratedImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<GeneratedImage> savedAll = captor.getValue();
        assertEquals(1, savedAll.size());
        GeneratedImage saved = savedAll.get(0);
        assertEquals("https://cdn.example.com/persisted.png", saved.getImageUrl());
        assertEquals("https://cdn.example.com/persisted.png", item.getUrl());
    }

    @Test
    void saveBatchStoresBase64ThroughImageStorage() {

        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        ImageAsset stored = new ImageAsset();
        stored.setImageUrl("https://cdn.example.com/base64.png");
        when(imageStorageService.storeReference(item.getUrl())).thenReturn(stored);

        ImageGenerationsResponse resp = new ImageGenerationsResponse(1234567890L, "gpt-image-2", Arrays.asList(item), null);
        generatedImageService.saveBatch(1L, "prompt", "1024x1024", resp);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeneratedImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<GeneratedImage> savedAll = captor.getValue();
        assertEquals(1, savedAll.size());
        GeneratedImage saved = savedAll.get(0);
        assertEquals("https://cdn.example.com/base64.png", saved.getImageUrl());
    }

    @Test
    void saveBatchPersistsB64JsonOnlyResponses() {
        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setB64Json("aGVsbG8=");
        ImageAsset stored = new ImageAsset();
        stored.setImageUrl("https://cdn.example.com/from-b64.png");
        when(imageStorageService.storeReference("data:image/png;base64,aGVsbG8=")).thenReturn(stored);

        generatedImageService.saveBatch(1L, "prompt", "1024x1024",
                new ImageGenerationsResponse(1L, "model", Arrays.asList(item), null));

        assertEquals("https://cdn.example.com/from-b64.png", item.getUrl());
        assertEquals(null, item.getB64Json());
    }

    @Test
    void saveBatchReturnsThePersistedUrlInsteadOfInlineData() {
        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setUrl("data:image/png;base64,aGVsbG8=");
        item.setB64Json("aGVsbG8=");
        ImageAsset stored = new ImageAsset();
        stored.setImageUrl("https://cdn.example.com/inline.png");
        when(imageStorageService.storeReference(item.getUrl())).thenReturn(stored);
        ImageGenerationsResponse response = new ImageGenerationsResponse(
                1234567890L, "gpt-image-2", Arrays.asList(item), null);

        generatedImageService.saveBatch(1L, "prompt", "1024x1024", response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeneratedImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals("https://cdn.example.com/inline.png", captor.getValue().get(0).getImageUrl());
        assertEquals("https://cdn.example.com/inline.png", item.getUrl());
        assertEquals(null, item.getB64Json());
    }

    @Test
    void saveBatchRejectsTheEntireResponseWhenStorageFailsWithoutLeakingUpstreamUrls() {
        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setUrl("https://upstream.example.com/img.png");
        when(imageStorageService.storeReference(anyString()))
                .thenThrow(new com.example.website.common.BusinessException(502, "Image upload failed"));

        ImageGenerationsResponse resp = new ImageGenerationsResponse(
                1234567890L, "gpt-image-2", Arrays.asList(item), null);
        assertThrows(com.example.website.common.BusinessException.class,
                () -> generatedImageService.saveBatch(1L, "prompt", "1024x1024", resp));

        verify(repository, never()).saveAll(any());
        assertEquals("https://upstream.example.com/img.png", item.getUrl());
    }

    @Test
    void saveBatchRejectsPartialStorageFailureWithoutSavingPartialHistory() {
        ImageGenerationsResponse.ImageDataItem first = new ImageGenerationsResponse.ImageDataItem(
                "https://upstream.example/first.png", null, null);
        ImageGenerationsResponse.ImageDataItem second = new ImageGenerationsResponse.ImageDataItem(
                "https://upstream.example/second.png", null, null);
        ImageAsset stored = new ImageAsset();
        stored.setImageUrl("https://cdn.example/first.png");
        when(imageStorageService.storeReference(first.getUrl())).thenReturn(stored);
        when(imageStorageService.storeReference(second.getUrl()))
                .thenThrow(new com.example.website.common.BusinessException(502, "Image upload failed"));

        ImageGenerationsResponse response = new ImageGenerationsResponse(
                1L, "model", Arrays.asList(first, second), null);
        assertThrows(com.example.website.common.BusinessException.class,
                () -> generatedImageService.saveBatch(1L, "prompt", "1024x1024", response));

        verify(repository, never()).saveAll(any());
        assertEquals("https://upstream.example/first.png", first.getUrl());
        assertEquals("https://upstream.example/second.png", second.getUrl());
    }

}
