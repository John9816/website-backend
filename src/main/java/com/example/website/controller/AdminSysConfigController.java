package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.SysConfigRequest;
import com.example.website.dto.SysConfigView;
import com.example.website.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/admin/configs")
@RequiredArgsConstructor
public class AdminSysConfigController {

    private final SysConfigService configService;

    @GetMapping
    public ApiResponse<List<SysConfigView>> list() {
        return ApiResponse.ok(configService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<SysConfigView> get(@PathVariable Long id) {
        return ApiResponse.ok(configService.get(id));
    }

    @PostMapping
    public ApiResponse<SysConfigView> create(@Valid @RequestBody SysConfigRequest req) {
        return ApiResponse.ok(configService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<SysConfigView> update(@PathVariable Long id,
                                             @Valid @RequestBody SysConfigRequest req) {
        return ApiResponse.ok(configService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return ApiResponse.ok();
    }
}
