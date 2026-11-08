package com.example.website.module.images.application;

import com.example.website.module.membership.application.MembershipService;

import com.example.website.common.BusinessException;
import com.example.website.module.images.api.dto.ImageGenerateRequest;
import com.example.website.module.images.api.dto.ImageGenerationsResponse;
import com.example.website.module.images.api.dto.ImageTaskView;
import com.example.website.module.images.infrastructure.persistence.ImageGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

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
    void submitRequiresActiveMembershipBeforeCreatingTask() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        org.mockito.Mockito.doThrow(new BusinessException(403, "需要有效会员才能使用 AI 生图，请先兑换会员卡"))
                .when(membership)
                .requireActiveMembership(3L, "需要有效会员才能使用 AI 生图，请先兑换会员卡");

        ImageTaskService service = new ImageTaskService(
                repository, history, primary, membership, Runnable::run, new ObjectMapper());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(3L, request("draw")));

        assertEquals(403, error.getCode());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void submitRejectsSecondImageOperationOnTheSameDay() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        when(repository.countByUserIdCreatedOrUpdatedSince(eq(3L), any(java.time.LocalDateTime.class)))
                .thenReturn(1L);

        ImageTaskService service = new ImageTaskService(
                repository, history, primary, membership, Runnable::run, new ObjectMapper());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(3L, request("draw again")));

        assertEquals(429, error.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void administratorBypassesMembershipAndDailyImageLimit() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        when(membership.isAdministrator(3L)).thenReturn(true);
        when(repository.countByUserIdCreatedOrUpdatedSince(eq(3L), any(java.time.LocalDateTime.class)))
                .thenReturn(100L);
        when(repository.save(any())).thenAnswer(invocation -> {
            com.example.website.module.images.domain.ImageGenerationTask task = invocation.getArgument(0);
            task.setId(13L);
            return task;
        });
        when(repository.findById(13L)).thenReturn(java.util.Optional.empty());
        when(primary.resolveModel(null)).thenReturn("configured-model");

        ImageTaskService service = new ImageTaskService(
                repository, history, primary, membership, Runnable::run, new ObjectMapper());

        ImageTaskView view = service.submit(3L, request("admin draw"));

        assertEquals(com.example.website.module.images.domain.ImageGenerationTask.STATUS_PENDING, view.getStatus());
        verify(membership, never()).requireActiveMembership(any(Long.class), any(String.class));
        verify(repository).save(any());
    }

    @Test
    void failedTaskCannotBeRetriedAfterUsingTodayQuota() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        com.example.website.module.images.domain.ImageGenerationTask task = new com.example.website.module.images.domain.ImageGenerationTask();
        task.setId(12L);
        task.setUserId(3L);
        task.setPrompt("retry me");
        task.setModel("configured-model");
        task.setN(1);
        task.setStatus(com.example.website.module.images.domain.ImageGenerationTask.STATUS_FAILED);
        when(repository.findByIdAndUserId(12L, 3L)).thenReturn(java.util.Optional.of(task));
        when(repository.countByUserIdCreatedOrUpdatedSince(eq(3L), any(java.time.LocalDateTime.class)))
                .thenReturn(1L);

        ImageTaskService service = new ImageTaskService(
                repository, history, primary, membership, Runnable::run, new ObjectMapper());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.retry(3L, 12L));

        assertEquals(429, error.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void completedTaskReturnsOnlyThePersistedUrl() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        AtomicReference<com.example.website.module.images.domain.ImageGenerationTask> savedTask = new AtomicReference<>();
        ImageGenerationsResponse upstream = new ImageGenerationsResponse(
                1L, "model", Collections.singletonList(new ImageGenerationsResponse.ImageDataItem(
                "https://upstream.example/image.png", null, null)), null);

        when(repository.save(any())).thenAnswer(invocation -> {
            com.example.website.module.images.domain.ImageGenerationTask saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(11L);
            savedTask.set(saved);
            return saved;
        });
        when(repository.findById(11L)).thenAnswer(invocation -> java.util.Optional.of(savedTask.get()));
        when(primary.resolveModel(null)).thenReturn("model");
        when(primary.generate(any())).thenReturn(upstream);

        ImageGenerateRequest request = request("draw");
        request.setSize("1024x1024");
        ImageTaskService service = new ImageTaskService(
                repository, history, primary, membership, Runnable::run, new ObjectMapper());
        doAnswer(invocation -> {
            ImageGenerationsResponse result = invocation.getArgument(3);
            result.getData().get(0).setUrl("https://cdn.example/persisted.png");
            return result;
        }).when(history).saveBatch(eq(3L), eq("draw"), eq("1024x1024"), any());

        ImageTaskView view = service.submit(3L, request);

        assertEquals(com.example.website.module.images.domain.ImageGenerationTask.STATUS_COMPLETED, view.getStatus());
        assertEquals("https://cdn.example/persisted.png", view.getResult().getData().get(0).getUrl());
    }

    @Test
    void storageFailureMarksTaskFailedAndDoesNotExposeUpstreamUrlAsResult() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        AtomicReference<com.example.website.module.images.domain.ImageGenerationTask> savedTask = new AtomicReference<>();
        ImageGenerationsResponse upstream = new ImageGenerationsResponse(1L, "model",
                Collections.singletonList(new ImageGenerationsResponse.ImageDataItem(
                        "https://upstream.example/image.png", null, null)), null);
        when(repository.save(any())).thenAnswer(invocation -> {
            com.example.website.module.images.domain.ImageGenerationTask saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(21L);
            savedTask.set(saved);
            return saved;
        });
        when(repository.findById(21L)).thenAnswer(invocation -> java.util.Optional.of(savedTask.get()));
        when(primary.resolveModel(null)).thenReturn("model");
        when(primary.generate(any())).thenReturn(upstream);
        doThrow(new BusinessException(502, "Image upload failed"))
                .when(history).saveBatch(eq(3L), eq("draw"), any(), any());

        ImageTaskView view = new ImageTaskService(repository, history, primary, membership,
                Runnable::run, new ObjectMapper()).submit(3L, request("draw"));

        assertEquals(com.example.website.module.images.domain.ImageGenerationTask.STATUS_FAILED, view.getStatus());
        assertNull(view.getResult());
        assertEquals("https://upstream.example/image.png", upstream.getData().get(0).getUrl());
        verify(primary).generate(any());
    }

    @Test
    void storageRetryDoesNotRegenerateThePaidImage() throws Exception {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        GeneratedImageService history = mock(GeneratedImageService.class);
        ImageService primary = mock(ImageService.class);
        MembershipService membership = mock(MembershipService.class);
        ImageGenerationsResponse upstream = new ImageGenerationsResponse(1L, "model",
                Collections.singletonList(new ImageGenerationsResponse.ImageDataItem(
                        "https://upstream.example/image.png", null, null)), null);
        com.example.website.module.images.domain.ImageGenerationTask task = new com.example.website.module.images.domain.ImageGenerationTask();
        task.setId(22L);
        task.setUserId(3L);
        task.setPrompt("draw");
        task.setModel("model");
        task.setN(1);
        task.setStatus(com.example.website.module.images.domain.ImageGenerationTask.STATUS_FAILED);
        task.setResultJson(new ObjectMapper().writeValueAsString(new ImageTaskService.StorageRetryPayload(upstream)));
        when(repository.findByIdAndUserId(22L, 3L)).thenReturn(java.util.Optional.of(task));
        when(repository.findById(22L)).thenReturn(java.util.Optional.of(task));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            ImageGenerationsResponse result = invocation.getArgument(3);
            result.getData().get(0).setUrl("https://cdn.example/retried.png");
            return result;
        }).when(history).saveBatch(eq(3L), eq("draw"), any(), any());

        ImageTaskView view = new ImageTaskService(repository, history, primary, membership,
                Runnable::run, new ObjectMapper()).retry(3L, 22L);

        assertEquals(com.example.website.module.images.domain.ImageGenerationTask.STATUS_COMPLETED, view.getStatus());
        assertEquals("https://cdn.example/retried.png", view.getResult().getData().get(0).getUrl());
        verify(primary, never()).generate(any());
        verify(membership, never()).requireActiveMembership(any(Long.class), any(String.class));
    }

    @Test
    void recoverableTasksIncludeActiveAndFailedWork() {
        ImageGenerationTaskRepository repository = mock(ImageGenerationTaskRepository.class);
        com.example.website.module.images.domain.ImageGenerationTask task = new com.example.website.module.images.domain.ImageGenerationTask();
        task.setId(7L);
        task.setUserId(3L);
        task.setPrompt("persistent task");
        task.setModel("configured-model");
        task.setN(1);
        task.setStatus(com.example.website.module.images.domain.ImageGenerationTask.STATUS_PROCESSING);
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
        com.example.website.module.images.domain.ImageGenerationTask task = new com.example.website.module.images.domain.ImageGenerationTask();
        task.setId(9L);
        task.setUserId(3L);
        task.setPrompt("cancel me");
        task.setModel("configured-model");
        task.setStatus(com.example.website.module.images.domain.ImageGenerationTask.STATUS_PROCESSING);
        when(repository.findByIdAndUserId(9L, 3L)).thenReturn(java.util.Optional.of(task));

        service(repository, mock(ImageService.class)).delete(3L, 9L);

        assertEquals(com.example.website.module.images.domain.ImageGenerationTask.STATUS_CANCELLED, task.getStatus());
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
                mock(MembershipService.class),
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
