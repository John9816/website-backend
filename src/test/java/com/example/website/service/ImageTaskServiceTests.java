package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.repository.ImageGenerationTaskRepository;
import com.example.website.service.content.FallbackCoverImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private ImageTaskService service(ImageService primary, FallbackCoverImageService fallback) {
        return new ImageTaskService(
                mock(ImageGenerationTaskRepository.class),
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
