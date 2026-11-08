package com.example.website.module.images.application;

import com.example.website.module.membership.application.MembershipService;

import com.example.website.common.BusinessException;
import com.example.website.module.images.api.dto.ImageEditRequest;
import com.example.website.module.images.api.dto.ImageGenerateRequest;
import com.example.website.module.images.api.dto.ImageGenerationsResponse;
import com.example.website.module.images.api.dto.ImageTaskView;
import com.example.website.module.images.domain.ImageGenerationTask;
import com.example.website.module.images.infrastructure.persistence.ImageGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.example.website.shared.dto.PageView;

@Slf4j
@Service
public class ImageTaskService {

    private static final int DAILY_GENERATION_LIMIT = 1;

    private final ImageGenerationTaskRepository taskRepository;
    private final GeneratedImageService historyService;
    private final ImageService imageService;
    private final MembershipService membershipService;
    private final Executor imageGenExecutor;
    private final ObjectMapper objectMapper;
    private final Object dailyLimitLock = new Object();

    public ImageTaskService(ImageGenerationTaskRepository taskRepository,
                            GeneratedImageService historyService,
                            ImageService imageService,
                            MembershipService membershipService,
                            Executor imageGenExecutor,
                            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.historyService = historyService;
        this.imageService = imageService;
        this.membershipService = membershipService;
        this.imageGenExecutor = imageGenExecutor;
        this.objectMapper = objectMapper;
    }

    public ImageTaskView submit(Long userId, ImageGenerateRequest req) {
        requireImageAccess(userId, "需要有效会员才能使用 AI 生图，请先兑换会员卡");
        ImageGenerationTask task;
        synchronized (dailyLimitLock) {
            checkDailyLimit(userId);

            String model = imageService.resolveModel(req.getModel());
            int n = req.getN() != null ? req.getN() : 1;

            task = new ImageGenerationTask();
            task.setUserId(userId);
            task.setPrompt(req.getPrompt());
            task.setSize(req.getSize());
            task.setN(n);
            task.setModel(model);
            task.setStatus(ImageGenerationTask.STATUS_PENDING);
            task = taskRepository.save(task);
        }

        final Long taskId = task.getId();
        imageGenExecutor.execute(() -> process(taskId, userId, req));

        return ImageTaskView.from(task);
    }

    public ImageTaskView submitEdit(Long userId, ImageEditRequest req) {
        requireImageAccess(userId, "需要有效会员才能使用 AI 图片编辑，请先兑换会员卡");
        ImageGenerationTask task;
        synchronized (dailyLimitLock) {
            checkDailyLimit(userId);

            String model = imageService.resolveModel(req.getModel());
            int n = req.getN() != null ? req.getN() : 1;

            task = new ImageGenerationTask();
            task.setUserId(userId);
            task.setPrompt(req.getPrompt());
            task.setSize(req.getSize());
            task.setN(n);
            task.setModel(model);
            task.setStatus(ImageGenerationTask.STATUS_PENDING);
            task = taskRepository.save(task);
        }

        final Long taskId = task.getId();
        imageGenExecutor.execute(() -> processEdit(taskId, userId, req));

        return ImageTaskView.from(task);
    }

    public ImageTaskView status(Long userId, Long taskId) {
        ImageGenerationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        return ImageTaskView.from(task);
    }

    public PageView<ImageTaskView> listRecoverable(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<ImageGenerationTask> result = taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId,
                Arrays.asList(
                        ImageGenerationTask.STATUS_PENDING,
                        ImageGenerationTask.STATUS_PROCESSING,
                        ImageGenerationTask.STATUS_FAILED),
                PageRequest.of(safePage, safeSize));
        return new PageView<>(
                result.getContent().stream().map(ImageTaskView::from).collect(Collectors.toList()),
                result.getTotalElements(),
                result.getNumber(),
                result.getSize());
    }

    public ImageTaskView retry(Long userId, Long taskId) {
        ImageGenerationTask task;
        synchronized (dailyLimitLock) {
            task = taskRepository.findByIdAndUserId(taskId, userId)
                    .orElseThrow(() -> new BusinessException(404, "Task not found"));
            if (!ImageGenerationTask.STATUS_FAILED.equals(task.getStatus())) {
                throw new BusinessException(400, "Only failed tasks can be retried");
            }
            ImageGenerationsResponse persistedRetry = storageRetryPayload(task.getResultJson());
            if (persistedRetry != null) {
                task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
                task.setErrorMessage(null);
                task.setCompletedAt(null);
                task = taskRepository.save(task);
                final Long persistedRetryTaskId = task.getId();
                final ImageGenerationTask retryTask = task;
                imageGenExecutor.execute(() -> persistExistingResult(persistedRetryTaskId, userId, retryTask, persistedRetry));
                return ImageTaskView.from(task);
            }
            requireImageAccess(userId, "需要有效会员才能重试 AI 生图任务，请先兑换会员卡");
            checkDailyLimit(userId);

            task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
            task.setErrorMessage(null);
            task.setResultJson(null);
            task.setCompletedAt(null);
            task = taskRepository.save(task);
        }

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt(task.getPrompt());
        req.setModel(task.getModel());
        req.setSize(task.getSize());
        req.setN(task.getN());

        final Long retryTaskId = task.getId();
        imageGenExecutor.execute(() -> process(retryTaskId, userId, req));

        return ImageTaskView.from(task);
    }

    private void checkDailyLimit(Long userId) {
        if (membershipService.isAdministrator(userId)) {
            return;
        }
        long todayTaskCount = taskRepository.countByUserIdCreatedOrUpdatedSince(
                userId, LocalDate.now().atStartOfDay());
        if (todayTaskCount >= DAILY_GENERATION_LIMIT) {
            throw new BusinessException(429, "今日 AI 生图次数已达上限（" + DAILY_GENERATION_LIMIT + " 次），请明天再试");
        }
    }

    private void requireImageAccess(Long userId, String membershipMessage) {
        if (membershipService.isAdministrator(userId)) {
            return;
        }
        membershipService.requireActiveMembership(userId, membershipMessage);
    }

    public void delete(Long userId, Long taskId) {
        ImageGenerationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        task.setStatus(ImageGenerationTask.STATUS_CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        taskRepository.save(task);
    }

    private void process(Long taskId, Long userId, ImageGenerateRequest req) {
        ImageGenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
        taskRepository.save(task);

        try {
            ImageGenerationsResponse resp = generateWithFallback(req);
            if (cancelledOrDeleted(taskId)) return;
            persistAndComplete(taskId, userId, task, req.getPrompt(), req.getSize(), resp);

        } catch (Exception e) {
            if (cancelledOrDeleted(taskId)) return;
            log.error("Image generation task {} failed: {}", taskId, e.getMessage());
            task.setStatus(ImageGenerationTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    ImageGenerationsResponse generateWithFallback(ImageGenerateRequest req) {
        ImageGenerationsResponse response = imageService.generate(req);
        if (response != null && response.getData() != null && !response.getData().isEmpty()) {
            return response;
        }
        throw new BusinessException(502, "Image API returned no image");
    }

    private void processEdit(Long taskId, Long userId, ImageEditRequest req) {
        ImageGenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
        taskRepository.save(task);

        try {
            ImageGenerationsResponse resp = imageService.edit(req);
            if (cancelledOrDeleted(taskId)) return;
            persistAndComplete(taskId, userId, task, req.getPrompt(), req.getSize(), resp);

        } catch (Exception e) {
            if (cancelledOrDeleted(taskId)) return;
            log.error("Image edit task {} failed: {}", taskId, e.getMessage());
            task.setStatus(ImageGenerationTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    private void persistExistingResult(Long taskId, Long userId, ImageGenerationTask task,
                                       ImageGenerationsResponse upstreamResult) {
        try {
            if (cancelledOrDeleted(taskId)) return;
            persistAndComplete(taskId, userId, task, task.getPrompt(), task.getSize(), upstreamResult);
        } catch (Exception e) {
            if (!cancelledOrDeleted(taskId)) {
                markStorageFailed(task, upstreamResult, e);
            }
        }
    }

    private void persistAndComplete(Long taskId, Long userId, ImageGenerationTask task,
                                    String prompt, String size, ImageGenerationsResponse response) throws Exception {
        try {
            historyService.saveBatch(userId, prompt, size, response);
        } catch (Exception storageFailure) {
            markStorageFailed(task, response, storageFailure);
            return;
        }
        if (cancelledOrDeleted(taskId)) return;
        task.setResultJson(objectMapper.writeValueAsString(response));
        task.setStatus(ImageGenerationTask.STATUS_COMPLETED);
        task.setErrorMessage(null);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    private void markStorageFailed(ImageGenerationTask task, ImageGenerationsResponse upstreamResult, Exception error) {
        try {
            task.setResultJson(objectMapper.writeValueAsString(new StorageRetryPayload(upstreamResult)));
        } catch (Exception serializationFailure) {
            task.setResultJson(null);
        }
        task.setStatus(ImageGenerationTask.STATUS_FAILED);
        String message = error.getMessage() == null ? "Image storage failed" : error.getMessage();
        task.setErrorMessage("Image storage failed: " + message.substring(0, Math.min(message.length(), 950)));
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    private ImageGenerationsResponse storageRetryPayload(String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) return null;
        try {
            return objectMapper.readValue(resultJson, StorageRetryPayload.class).getUpstreamResult();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static class StorageRetryPayload {
        private ImageGenerationsResponse upstreamResult;

        public StorageRetryPayload() {
        }

        public StorageRetryPayload(ImageGenerationsResponse upstreamResult) {
            this.upstreamResult = upstreamResult;
        }

        public ImageGenerationsResponse getUpstreamResult() {
            return upstreamResult;
        }

        public void setUpstreamResult(ImageGenerationsResponse upstreamResult) {
            this.upstreamResult = upstreamResult;
        }
    }

    private boolean cancelledOrDeleted(Long taskId) {
        return taskRepository.findById(taskId)
                .map(task -> ImageGenerationTask.STATUS_CANCELLED.equals(task.getStatus()))
                .orElse(true);
    }
}
