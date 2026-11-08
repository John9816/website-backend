package com.example.website.module.images.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.common.BusinessException;
import com.example.website.module.images.api.dto.GeneratedImageView;
import com.example.website.module.images.api.dto.ImageEditRequest;
import com.example.website.module.images.api.dto.ImageGenerateRequest;
import com.example.website.module.images.api.dto.ImageModelView;
import com.example.website.module.images.api.dto.ImageTaskView;
import com.example.website.shared.dto.PageView;
import com.example.website.module.images.application.GeneratedImageService;
import com.example.website.module.images.application.ImageService;
import com.example.website.module.images.application.ImageTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping({"/api/admin/image", "/api/user/image"})
@RequiredArgsConstructor
public class AdminImageController {

    private final ImageTaskService imageTaskService;
    private final GeneratedImageService historyService;
    private final ImageService imageService;

    @GetMapping("/models")
    public ApiResponse<List<ImageModelView>> models(HttpServletRequest request) {
        currentUserId(request);
        return ApiResponse.ok(imageService.listModels());
    }

    @PostMapping("/generate")
    public ApiResponse<ImageTaskView> generate(HttpServletRequest request,
                                               @Valid @RequestBody ImageGenerateRequest req) {
        Long userId = currentUserId(request);
        ImageTaskView task = imageTaskService.submit(userId, req);
        return ApiResponse.ok(task);
    }

    @PostMapping("/edit")
    public ApiResponse<ImageTaskView> edit(HttpServletRequest request,
                                           @RequestParam("image") MultipartFile image,
                                           @RequestParam("prompt") String prompt,
                                           @RequestParam(required = false) MultipartFile mask,
                                           @RequestParam(required = false) String model,
                                           @RequestParam(required = false) Integer n,
                                           @RequestParam(required = false) String size) {
        Long userId = currentUserId(request);
        ImageEditRequest req = buildEditRequest(image, prompt, mask, model, n, size);
        ImageTaskView task = imageTaskService.submitEdit(userId, req);
        return ApiResponse.ok(task);
    }

    @GetMapping("/generate/{taskId}")
    public ApiResponse<ImageTaskView> taskStatus(HttpServletRequest request,
                                                 @PathVariable Long taskId) {
        Long userId = currentUserId(request);
        return ApiResponse.ok(imageTaskService.status(userId, taskId));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageView<ImageTaskView>> tasks(HttpServletRequest request,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(imageTaskService.listRecoverable(currentUserId(request), page, size));
    }

    @GetMapping("/history")
    public ApiResponse<PageView<GeneratedImageView>> history(HttpServletRequest request,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(historyService.list(currentUserId(request), page, size));
    }

    @PatchMapping("/history/{id}/share")
    public ApiResponse<GeneratedImageView> toggleShare(HttpServletRequest request,
                                                       @PathVariable Long id,
                                                       @RequestParam boolean shared) {
        Long userId = currentUserId(request);
        return ApiResponse.ok(historyService.toggleShare(userId, id, shared));
    }

    @PostMapping("/history/{id}/retry")
    public ApiResponse<ImageTaskView> retry(HttpServletRequest request, @PathVariable Long id) {
        Long userId = currentUserId(request);
        return ApiResponse.ok(imageTaskService.retry(userId, Math.abs(id)));
    }

    @DeleteMapping("/history/{id}")
    public ApiResponse<Void> deleteHistory(HttpServletRequest request, @PathVariable Long id) {
        Long userId = currentUserId(request);
        if (id < 0) {
            imageTaskService.delete(userId, Math.abs(id));
        } else {
            historyService.delete(userId, id);
        }
        return ApiResponse.ok();
    }

    private Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        return userId;
    }

    private ImageEditRequest buildEditRequest(MultipartFile image,
                                              String prompt,
                                              MultipartFile mask,
                                              String model,
                                              Integer n,
                                              String size) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(400, "image is required");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new BusinessException(400, "prompt is required");
        }
        if (prompt.length() > 2000) {
            throw new BusinessException(400, "prompt must be at most 2000 characters");
        }
        if (model != null && model.length() > 100) {
            throw new BusinessException(400, "model must be at most 100 characters");
        }
        if (n != null && (n < 1 || n > 3)) {
            throw new BusinessException(400, "n must be between 1 and 3");
        }
        if (size != null && !size.isEmpty()
                && !size.matches("(?i)(auto|(?:\\d+(?:x|\\*)\\d+)|(?:1:1|4:5|3:4|2:3|3:2|4:3|16:9|9:16|21:9))")) {
            throw new BusinessException(400, "size must be auto, a supported ratio, WIDTHxHEIGHT, or WIDTH*HEIGHT");
        }

        ImageEditRequest req = new ImageEditRequest();
        req.setPrompt(prompt);
        req.setModel(model);
        req.setN(n);
        req.setSize(size);
        req.setImageFilename(image.getOriginalFilename());
        req.setImageContentType(image.getContentType());
        try {
            req.setImageBytes(image.getBytes());
            if (mask != null && !mask.isEmpty()) {
                req.setMaskBytes(mask.getBytes());
                req.setMaskFilename(mask.getOriginalFilename());
                req.setMaskContentType(mask.getContentType());
            }
        } catch (IOException e) {
            throw new BusinessException(400, "Failed to read uploaded image: " + e.getMessage());
        }
        return req;
    }
}
