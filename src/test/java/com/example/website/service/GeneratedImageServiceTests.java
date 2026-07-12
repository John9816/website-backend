package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.entity.GeneratedImage;
import com.example.website.repository.GeneratedImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageServiceTests {

    @Mock
    private GeneratedImageRepository repository;

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private GoFileClient goFileClient;

    private GeneratedImageService generatedImageService;

    @BeforeEach
    void setUp() {
        TransactionTemplate inlineTxTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        generatedImageService = new GeneratedImageService(repository, sysConfigService, goFileClient, inlineTxTemplate);
    }

    @Test
    void saveBatchStoresRemoteUrlDirectlyByDefault() {
        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setUrl("https://upstream.example.com/img.png");

        ImageGenerationsResponse resp = new ImageGenerationsResponse(1234567890L, "dall-e-3", Arrays.asList(item), null);
        generatedImageService.saveBatch(1L, "prompt", "1024x1024", resp);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeneratedImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<GeneratedImage> savedAll = captor.getValue();
        assertEquals(1, savedAll.size());
        GeneratedImage saved = savedAll.get(0);
        assertEquals("https://upstream.example.com/img.png", saved.getImageUrl());
        verify(goFileClient, never()).downloadBytes(anyString());
        verify(goFileClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void saveBatchStoresBase64ViaLocalDiskWhenGoFileNotConfigured() {
        when(sysConfigService.getValue(ImageService.CFG_GOFILE_URL)).thenReturn(Optional.empty());
        System.setProperty("website.image.upload-dir", "target/test-uploads");

        ImageGenerationsResponse.ImageDataItem item = new ImageGenerationsResponse.ImageDataItem();
        item.setUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

        ImageGenerationsResponse resp = new ImageGenerationsResponse(1234567890L, "gpt-image-2", Arrays.asList(item), null);
        generatedImageService.saveBatch(1L, "prompt", "1024x1024", resp);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeneratedImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<GeneratedImage> savedAll = captor.getValue();
        assertEquals(1, savedAll.size());
        GeneratedImage saved = savedAll.get(0);
        assertEquals(true, saved.getImageUrl().startsWith("/api/v1/image/file/") && saved.getImageUrl().endsWith(".png"),
                "Expected local file URL, got: " + saved.getImageUrl());
        System.clearProperty("website.image.upload-dir");
    }

    @Test
    void checkDailyLimitAllowsWhenUnderLimit() {
        when(repository.countByUserIdSince(eq(1L), any(LocalDateTime.class))).thenReturn(50L);
        generatedImageService.checkDailyLimit(1L);
    }

    @Test
    void checkDailyLimitThrowsWhenAtLimit() {
        when(repository.countByUserIdSince(eq(2L), any(LocalDateTime.class))).thenReturn(100L);
        assertThrows(BusinessException.class, () -> generatedImageService.checkDailyLimit(2L));
    }

    @Test
    void checkDailyLimitThrowsWhenOverLimit() {
        when(repository.countByUserIdSince(eq(3L), any(LocalDateTime.class))).thenReturn(150L);
        assertThrows(BusinessException.class, () -> generatedImageService.checkDailyLimit(3L));
    }
}
