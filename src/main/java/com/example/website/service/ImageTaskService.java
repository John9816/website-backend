package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageEditRequest;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.dto.ImageTaskView;
import com.example.website.entity.ImageGenerationTask;
import com.example.website.repository.ImageGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ImageTaskService {

    private final ImageGenerationTaskRepository taskRepository;
    private final GeneratedImageService historyService;
    private final ImageService imageService;
    private final SysConfigService sysConfigService;
    private final Executor imageGenExecutor;
    private final ObjectMapper objectMapper;

    public ImageTaskService(ImageGenerationTaskRepository taskRepository,
                            GeneratedImageService historyService,
                            ImageService imageService,
                            SysConfigService sysConfigService,
                            Executor imageGenExecutor,
                            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.historyService = historyService;
        this.imageService = imageService;
        this.sysConfigService = sysConfigService;
        this.imageGenExecutor = imageGenExecutor;
        this.objectMapper = objectMapper;
    }

    public ImageTaskView submit(Long userId, ImageGenerateRequest req) {
        historyService.checkDailyLimit(userId);

        String model = sysConfigService.getValue(ImageService.CFG_MODEL).orElse("unknown");
        int n = req.getN() != null ? req.getN() : 1;

        ImageGenerationTask task = new ImageGenerationTask();
        task.setUserId(userId);
        task.setPrompt(req.getPrompt());
        task.setSize(req.getSize());
        task.setN(n);
        task.setModel(model);
        task.setStatus(ImageGenerationTask.STATUS_PENDING);
        task = taskRepository.save(task);

        final Long taskId = task.getId();
        imageGenExecutor.execute(() -> process(taskId, userId, req));

        return ImageTaskView.from(task);
    }

    public ImageTaskView submitEdit(Long userId, ImageEditRequest req) {
        historyService.checkDailyLimit(userId);

        String model = sysConfigService.getValue(ImageService.CFG_MODEL).orElse("unknown");
        int n = req.getN() != null ? req.getN() : 1;

        ImageGenerationTask task = new ImageGenerationTask();
        task.setUserId(userId);
        task.setPrompt(req.getPrompt());
        task.setSize(req.getSize());
        task.setN(n);
        task.setModel(model);
        task.setStatus(ImageGenerationTask.STATUS_PENDING);
        task = taskRepository.save(task);

        final Long taskId = task.getId();
        imageGenExecutor.execute(() -> processEdit(taskId, userId, req));

        return ImageTaskView.from(task);
    }

    public ImageTaskView status(Long userId, Long taskId) {
        ImageGenerationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        return ImageTaskView.from(task);
    }

    public ImageTaskView retry(Long userId, Long taskId) {
        ImageGenerationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        if (!ImageGenerationTask.STATUS_FAILED.equals(task.getStatus())) {
            throw new BusinessException(400, "Only failed tasks can be retried");
        }

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt(task.getPrompt());
        req.setSize(task.getSize());
        req.setN(task.getN());

        task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
        task.setErrorMessage(null);
        task.setResultJson(null);
        task.setCompletedAt(null);
        task = taskRepository.save(task);

        final Long retryTaskId = task.getId();
        imageGenExecutor.execute(() -> process(retryTaskId, userId, req));

        return ImageTaskView.from(task);
    }

    public void delete(Long userId, Long taskId) {
        ImageGenerationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        taskRepository.delete(task);
    }

    private void process(Long taskId, Long userId, ImageGenerateRequest req) {
        ImageGenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
        taskRepository.save(task);

        try {
            ImageGenerationsResponse resp = imageService.generate(req);
            String resultJson = objectMapper.writeValueAsString(resp);
            task.setResultJson(resultJson);
            task.setStatus(ImageGenerationTask.STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);

            try {
                historyService.saveBatch(userId, req.getPrompt(), req.getSize(), resp);
            } catch (Exception e) {
                log.warn("Image generated but failed to persist history for task {}: {}", taskId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Image generation task {} failed: {}", taskId, e.getMessage());
            task.setStatus(ImageGenerationTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    private void processEdit(Long taskId, Long userId, ImageEditRequest req) {
        ImageGenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setStatus(ImageGenerationTask.STATUS_PROCESSING);
        taskRepository.save(task);

        try {
            ImageGenerationsResponse resp = imageService.edit(req);
            String resultJson = objectMapper.writeValueAsString(resp);
            task.setResultJson(resultJson);
            task.setStatus(ImageGenerationTask.STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);

            try {
                historyService.saveBatch(userId, req.getPrompt(), req.getSize(), resp);
            } catch (Exception e) {
                log.warn("Image edited but failed to persist history for task {}: {}", taskId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Image edit task {} failed: {}", taskId, e.getMessage());
            task.setStatus(ImageGenerationTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 1000)) : "Unknown error");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }
}
