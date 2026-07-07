package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.common.BusinessException;
import com.example.website.dto.GeneratedImageView;
import com.example.website.dto.ImageEditRequest;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageTaskView;
import com.example.website.dto.PageView;
import com.example.website.service.GeneratedImageService;
import com.example.website.service.ImageTaskService;
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
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping({"/api/admin/image", "/api/user/image"})
@RequiredArgsConstructor
public class AdminImageController {

    private final ImageTaskService imageTaskService;
    private final GeneratedImageService historyService;

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
                                           @RequestParam(required = false) Integer n,
                                           @RequestParam(required = false) String size) {
        Long userId = currentUserId(request);
        ImageEditRequest req = buildEditRequest(image, prompt, mask, n, size);
        ImageTaskView task = imageTaskService.submitEdit(userId, req);
        return ApiResponse.ok(task);
    }

    @GetMapping("/generate/{taskId}")
    public ApiResponse<ImageTaskView> taskStatus(HttpServletRequest request,
                                                 @PathVariable Long taskId) {
        Long userId = currentUserId(request);
        return ApiResponse.ok(imageTaskService.status(userId, taskId));
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

    private ImageEditRequest buildEditRequest(MultipartFile image, String prompt, MultipartFile mask, Integer n, String size) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(400, "image is required");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new BusinessException(400, "prompt is required");
        }
        if (prompt.length() > 2000) {
            throw new BusinessException(400, "prompt must be at most 2000 characters");
        }
        if (n != null && (n < 1 || n > 10)) {
            throw new BusinessException(400, "n must be between 1 and 10");
        }
        if (size != null && !size.isEmpty() && !size.matches("\\d+x\\d+")) {
            throw new BusinessException(400, "size must be WIDTHxHEIGHT, e.g. 1024x1024");
        }

        ImageEditRequest req = new ImageEditRequest();
        req.setPrompt(prompt);
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
