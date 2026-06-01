package com.example.website.controller;

import com.example.website.service.GeneratedImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/image/file")
@RequiredArgsConstructor
public class ImageFileController {

    private final GeneratedImageService historyService;

    @GetMapping("/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) {
        byte[] data = historyService.readFile(filename);
        MediaType mediaType = MediaTypeFactory.getMediaType(filename)
                .orElse(MediaType.IMAGE_PNG);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length))
                .body(data);
    }
}
