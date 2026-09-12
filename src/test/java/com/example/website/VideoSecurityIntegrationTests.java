package com.example.website;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VideoSecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void videoCatalogAndPlaybackRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/videos/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/v1/videos/search").param("keyword", "消失的人"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/v1/videos/play").param("id", "333692"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("需要登录才能播放"));
        mockMvc.perform(get("/api/v1/videos/333692/detail"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/v1/videos/333692/play"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("需要登录才能播放"));
    }
}
