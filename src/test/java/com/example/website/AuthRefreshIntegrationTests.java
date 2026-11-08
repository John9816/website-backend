package com.example.website;

import com.example.website.module.identity.domain.User;
import com.example.website.module.identity.infrastructure.persistence.RefreshTokenRepository;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthRefreshIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void loginReturnsRefreshTokenAndRefreshRotatesIt() throws Exception {
        String username = "refresh_user_a";
        String password = "secret123";
        register(username, password);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private, max-age=0"))
                .andReturn();

        JsonNode loginData = readJson(loginResult).path("data");
        String access = loginData.path("token").asText();
        String refresh = loginData.path("refreshToken").asText();
        assertTrue(access.length() > 20);
        assertTrue(refresh.length() > 20);

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(username));

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode refreshed = readJson(refreshResult).path("data");
        String newAccess = refreshed.path("token").asText();
        String newRefresh = refreshed.path("refreshToken").asText();
        assertTrue(newAccess != null && !newAccess.isEmpty());
        // Access JWT may be byte-identical if issued in the same second; refresh must rotate.
        assertNotEquals(refresh, newRefresh);

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        // Old refresh must be rejected (rotation).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // Reuse detection revokes the replacement token family as well.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void refreshAcceptsCamelCaseBody() throws Exception {
        String username = "refresh_user_b";
        register(username, "secret123");
        String refresh = loginAndExtractRefresh(username, "secret123");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void invalidRefreshReturnsHttp401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void changePasswordRevokesRefreshTokens() throws Exception {
        String username = "refresh_user_c";
        String oldPassword = "secret123";
        String newPassword = "newpass456";
        register(username, oldPassword);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, oldPassword)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = readJson(loginResult).path("data");
        String access = data.path("token").asText();
        String refresh = data.path("refreshToken").asText();

        mockMvc.perform(post("/api/user/change-password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        User user = userRepository.findByUsername(username).orElseThrow(AssertionError::new);
        assertEquals(1, user.getAuthVersion());
        assertTrue(refreshTokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(user.getId()))
                .allMatch(t -> t.getRevokedAt() != null));
    }

    @Test
    void logoutRevokesPresentedRefreshToken() throws Exception {
        String username = "refresh_user_d";
        register(username, "secret123");
        String refresh = loginAndExtractRefresh(username, "secret123");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, password, qqEmail(username))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    private String loginAndExtractRefresh(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        String refresh = readJson(result).path("data").path("refreshToken").asText();
        assertFalse(refresh.isEmpty());
        return refresh;
    }

    private String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String registerBody(String username, String password, String email) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";
    }

    private String qqEmail(String seed) {
        CRC32 crc32 = new CRC32();
        crc32.update(seed.getBytes(StandardCharsets.UTF_8));
        long number = 10000L + (crc32.getValue() % 9999999999L);
        return number + "@qq.com";
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
