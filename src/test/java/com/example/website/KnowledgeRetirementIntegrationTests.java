package com.example.website;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeRetirementIntegrationTests {
    @Autowired private MockMvc mvc;

    @Test
    void anonymousLegacyRoutesAreGone() throws Exception { assertRetired(); }

    @Test
    @WithMockUser(roles = "ADMIN")
    void authenticatedLegacyRoutesCannotReadOrWrite() throws Exception { assertRetired(); }

    private void assertRetired() throws Exception {
        String[] paths = {"/api/user/kb/spaces", "/api/user/kb/docs/1", "/api/user/kb/tags",
                "/api/admin/kb/docs", "/api/admin/kb/assets", "/api/public/kb/share/old-token",
                "/api/v1/kb/assets/old.png", "/api/kb/documents/1", "/api/share/doc/old-token"};
        for (String path : paths) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
                mvc.perform(request(method, path)).andExpect(status().isGone())
                        .andExpect(jsonPath("$.code").value(410))
                        .andExpect(jsonPath("$.message").value("旧知识库已下线，请使用学城文档"))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
        }
    }
}
