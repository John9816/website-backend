package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.repository.ImageGenerationTaskRepository;
import com.example.website.service.content.FallbackCoverImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Arrays;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class ImageTaskServiceTests {

    @Test
    void generationUsesFallbackWhenPrimaryThrows() {
        ImageService primary = mock(ImageService.class);
        FallbackCoverImageService fallback = mock(FallbackCoverImageService.class);
        when(primary.generate(any())).thenThrow(new BusinessException(502, "primary unavailable"));
        when(fallback.generate("draw a lighthouse")).thenReturn("https://cdn.example/fallback.png");
        when(fallback.model()).thenReturn("GPT Image 2.0");

        ImageGenerationsResponse response = service(primary, fallback)
                .generateWithFallback(request("draw a lighthouse"));

        assertEquals("GPT Image 2.0", response.getModel());
        assertEquals("https://cdn.example/fallback.png", response.getData().get(0).getUrl());
    }

    @Test
    void generationKeepsPrimaryResultWithoutCallingFallback() {
        ImageService primary = mock(ImageService.class);
        FallbackCoverImageService fallback = mock(FallbackCoverImageService.class);
        ImageGenerationsResponse expected = new ImageGenerationsResponse(
                1L,
                "primary",
                Collections.singletonList(new ImageGenerationsResponse.ImageDataItem(
                        "https://cdn.example/primary.png", null, null)),
                null);
        when(primary.generate(any())).thenReturn(expected);

        assertEquals(expected, service(primary, fallback).generateWithFallback(request("draw")));
    }

    @Test
    void generationFailsOnlyAfterBothProvidersFail() {
        ImageService primary = mock(ImageService.class);
        FallbackCoverImageService fallback = mock(FallbackCoverImageService.class);
        when(primary.generate(any())).thenThrow(new BusinessException(502, "primary unavailable"));
        when(fallback.generate("draw")).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(primary, fallback).generateWithFallback(request("draw")));
        assertEquals(502, error.getCode());
    }

    @Test
    void recoverableTasksIncludeActiveAndFailedWork() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        com.example.website.entity.ImageGenerationTask task = new com.example.website.entity.ImageGenerationTask();
        task.setId(7L);
        task.setUserId(3L);
        task.setPrompt("persistent task");
        task.setModel("configured-model");
        task.setN(1);
        task.setStatus(com.example.website.entity.ImageGenerationTask.STATUS_PROCESSING);
        PageRequest pageable = PageRequest.of(0, 20);
        when(repository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                eq(3L),
                eq(Arrays.asList("PENDING", "PROCESSING", "FAILED")),
                eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.singletonList(task), pageable, 1));

        ImageTaskService service = service(repository, mock(ImageService.class), mock(FallbackCoverImageService.class));

        assertEquals(1, service.listRecoverable(3L, 0, 20).getTotal());
        assertEquals("persistent task", service.listRecoverable(3L, 0, 20).getItems().get(0).getPrompt());
    }

    private ImageTaskService service(ImageService primary, FallbackCoverImageService fallback) {
        return service(mock(ImageGenerationTaskRepository.class), primary, fallback);
    }

    private ImageTaskService service(ImageGenerationTaskRepository repository,
                                     ImageService primary,
                                     FallbackCoverImageService fallback) {
        return new ImageTaskService(
                repository,
                mock(GeneratedImageService.class),
                primary,
                fallback,
                mock(SysConfigService.class),
                Runnable::run,
                new ObjectMapper());
    }

    private ImageGenerateRequest request(String prompt) {
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt(prompt);
        request.setN(1);
        return request;
    }
}
