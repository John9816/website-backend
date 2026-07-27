package com.example.website;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserManagementIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void regularUserCannotManageUsers() throws Exception {
        String suffix = uniqueSuffix();
        String token = registerAndExtractToken("member_" + suffix, "secret123", qqEmail(suffix));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanCreateSearchDisableAndResetUser() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String suffix = uniqueSuffix();
        String username = "managed_" + suffix;
        String email = qqEmail(suffix);
        String oldPassword = "secret123";
        String newPassword = "secret456";

        MvcResult createdResult = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + oldPassword + "\",\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn();
        long userId = readJson(createdResult).path("data").path("id").asLong();

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("keyword", username)
                        .param("role", "USER")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value(username));

        String userToken = loginAndExtractToken(username, oldPassword);
        mockMvc.perform(put("/api/admin/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
        expectLoginUnauthorized(username, oldPassword);

        mockMvc.perform(put("/api/admin/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"enabled\":true}"))
                .andExpect(status().isOk());

        String enabledToken = loginAndExtractToken(username, oldPassword);
        mockMvc.perform(post("/api/admin/users/" + userId + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + enabledToken))
                .andExpect(status().isUnauthorized());
        expectLoginUnauthorized(username, oldPassword);
        loginAndExtractToken(username, newPassword);
    }

    @Test
    void adminDetailReportsActivityAndAssetCounts() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String suffix = uniqueSuffix();
        String username = "detail_" + suffix;
        String email = qqEmail(suffix);
        String password = "secret123";

        MvcResult createdResult = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + password + "\",\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long userId = readJson(createdResult).path("data").path("id").asLong();

        // Login sets last_login_at / last_login_ip / login_count.
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.loginCount").value(1))
                .andExpect(jsonPath("$.data.lastLoginIp").value("203.0.113.7"))
                .andExpect(jsonPath("$.data.lastLoginAt").isNotEmpty())
                .andExpect(jsonPath("$.data.activeSessions").value(1))
                .andExpect(jsonPath("$.data.imageCount").value(0))
                .andExpect(jsonPath("$.data.articleCount").value(0))
                .andExpect(jsonPath("$.data.kbDocCount").value(0))
                .andExpect(jsonPath("$.data.conversationCount").value(0))
                .andExpect(jsonPath("$.data.musicFavoriteCount").value(0))
                .andExpect(jsonPath("$.data.playlistCount").value(0))
                .andExpect(jsonPath("$.data.playHistoryCount").value(0));
    }

    @Test
    void regularUserCannotViewUserDetail() throws Exception {
        String suffix = uniqueSuffix();
        String token = registerAndExtractToken("viewer_" + suffix, "secret123", qqEmail(suffix));

        mockMvc.perform(get("/api/admin/users/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCannotDisableOrDemoteOwnAccount() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        MvcResult me = mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        long adminId = readJson(me).path("data").path("id").asLong();

        mockMvc.perform(put("/api/admin/users/" + adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/users/" + adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private String registerAndExtractToken(String username, String password, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private String loginAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private void expectLoginUnauthorized(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String uniqueSuffix() {
        String value = Long.toString(Math.abs(System.nanoTime()));
        return value.substring(Math.max(0, value.length() - 8));
    }

    private String qqEmail(String suffix) {
        return "8" + suffix + "@qq.com";
    }
}
