package com.example.website.module.distribution.api.controller;

import com.example.website.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppUpdateControllerTests {

    @TempDir
    Path tempDir;

    @Test
    void servesManifestAndApkFromConfiguredDirectory() throws Exception {
        AppProperties props = new AppProperties();
        props.getUpdate().setStorageDir(tempDir.toString());
        Files.write(tempDir.resolve("latest.json"), (
                "{\"version\":\"1.0.5\",\"buildNumber\":6," +
                        "\"downloadUrl\":\"https://example.test/updates/DuckMusic-v1.0.5.apk\"}"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("DuckMusic-v1.0.5.apk"), new byte[]{1, 2, 3});

        AppUpdateController controller = new AppUpdateController(props, new ObjectMapper());

        assertEquals(HttpStatus.OK, controller.latestManifest().getStatusCode());
        assertNotNull(controller.latestManifest().getBody());
        assertEquals(3L, controller.download("DuckMusic-v1.0.5.apk").getBody().contentLength());
        assertEquals(HttpStatus.NOT_FOUND, controller.download("../latest.json").getStatusCode());
    }
}
