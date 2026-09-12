package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.config.PerformanceMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/performance")
@RequiredArgsConstructor
public class AdminPerformanceController {

    private final PerformanceMetrics metrics;

    @GetMapping
    public ApiResponse<PerformanceMetrics.Snapshot> snapshot() {
        return ApiResponse.ok(metrics.snapshot());
    }
}
