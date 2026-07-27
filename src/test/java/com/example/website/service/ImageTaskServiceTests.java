package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.repository.ImageGenerationTaskRepository;
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
import static org.mockito.Mockito.verify;

class ImageTaskServiceTests {

    @Test
    void generationKeepsPrimaryResult() {
        ImageService primary = mock(ImageService.class);
        ImageGenerationsResponse expected = new ImageGenerationsResponse(
                1L,
                "primary",
                Collections.singletonList(new ImageGenerationsResponse.ImageDataItem(
                        "https://cdn.example/primary.png", null, null)),
                null);
        when(primary.generate(any())).thenReturn(expected);

        assertEquals(expected, service(primary).generateWithFallback(request("draw")));
    }

    @Test
    void generationFailsWhenPrimaryReturnsNoImage() {
        ImageService primary = mock(ImageService.class);
        ImageGenerationsResponse empty = new ImageGenerationsResponse(
                1L, "primary", Collections.emptyList(), null);
        when(primary.generate(any())).thenReturn(empty);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(primary).generateWithFallback(request("draw")));
        assertEquals(502, error.getCode());
    }

    @Test
    void generationPropagatesPrimaryError() {
        ImageService primary = mock(ImageService.class);
        when(primary.generate(any())).thenThrow(new BusinessException(502, "primary unavailable"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(primary).generateWithFallback(request("draw")));
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

        ImageTaskService service = service(repository, mock(ImageService.class));

        assertEquals(1, service.listRecoverable(3L, 0, 20).getTotal());
        assertEquals("persistent task", service.listRecoverable(3L, 0, 20).getItems().get(0).getPrompt());
    }

    @Test
    void deletingActiveTaskCancelsItInsteadOfAllowingWorkerToRecreateIt() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        com.example.website.entity.ImageGenerationTask task = new com.example.website.entity.ImageGenerationTask();
        task.setId(9L);
        task.setUserId(3L);
        task.setPrompt("cancel me");
        task.setModel("configured-model");
        task.setStatus(com.example.website.entity.ImageGenerationTask.STATUS_PROCESSING);
        when(repository.findByIdAndUserId(9L, 3L)).thenReturn(java.util.Optional.of(task));

        service(repository, mock(ImageService.class)).delete(3L, 9L);

        assertEquals(com.example.website.entity.ImageGenerationTask.STATUS_CANCELLED, task.getStatus());
        verify(repository).save(task);
    }

    private ImageTaskService service(ImageService primary) {
        return service(mock(ImageGenerationTaskRepository.class), primary);
    }

    private ImageTaskService service(ImageGenerationTaskRepository repository,
                                     ImageService primary) {
        return new ImageTaskService(
                repository,
                mock(GeneratedImageService.class),
                primary,
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
