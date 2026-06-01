package com.example.website;

import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Test
    void registerCreatesNormalUserAndReturnsToken() throws Exception {
        String username = "alice_test";
        String password = "secret123";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value(User.ROLE_USER))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        JsonNode json = readJson(result);
        String token = json.path("data").path("token").asText();
        assertTrue(token != null && !token.isEmpty());

        User user = userRepository.findByUsername(username).orElseThrow(AssertionError::new);
        org.junit.jupiter.api.Assertions.assertEquals(User.ROLE_USER, user.getRole());
        assertNotEquals(password, user.getPassword());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value(User.ROLE_USER));
    }

    @Test
    void malformedJsonReturnsBadRequestInsteadOfServerError() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\\\"username\\\":\\\"alice\\\",\\\"password\\\":\\\"secret123\\\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Malformed JSON request body. Example: {\"username\":\"alice\",\"password\":\"secret123\"}"
                ));
    }

    @Test
    void regularUserCannotAccessAdminManagementEndpointsButAdminCan() throws Exception {
        String userToken = registerAndExtractToken("bob_test", "secret123");
        String adminToken = loginAndExtractToken("admin", "admin123");

        mockMvc.perform(get("/api/admin/configs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/admin/configs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configKey\":\"demo.key\",\"configValue\":\"demo\",\"description\":\"demo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/configs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void regularUserCanAccessSelfServiceAndReadOnlyEndpoints() throws Exception {
        String username = "carol_test";
        String oldPassword = "secret123";
        String newPassword = "secret456";
        String userToken = registerAndExtractToken(username, oldPassword);

        mockMvc.perform(get("/api/admin/categories")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/admin/links")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/admin/image/history")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/admin/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value(User.ROLE_USER));
    }

    @Test
    void currentUserEndpointReturnsRoleAndSystemConfigVisibility() throws Exception {
        String userUsername = "erin_test";
        String userToken = registerAndExtractToken(userUsername, "secret123");
        String adminToken = loginAndExtractToken("admin", "admin123");

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(userUsername))
                .andExpect(jsonPath("$.data.role").value(User.ROLE_USER))
                .andExpect(jsonPath("$.data.canManageSystemConfig").value(false));

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value(User.ROLE_ADMIN))
                .andExpect(jsonPath("$.data.canManageSystemConfig").value(true));
    }

    @Test
    void categoriesAndLinksAreScopedToCurrentUserWhilePublicFallsBackToAdmin() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String userToken = registerAndExtractToken("dave_test", "secret123");

        String adminCategoryName = "admin-cat-001";
        String adminLinkName = "admin-link-001";
        String userCategoryName = "user-cat-001";
        String userLinkName = "user-link-001";

        Long adminCategoryId = createCategory(adminToken, "/api/admin/categories", adminCategoryName);
        createLink(adminToken, "/api/admin/links", adminCategoryId, adminLinkName);

        Long userCategoryId = createCategory(userToken, "/api/admin/categories", userCategoryName);
        createLink(userToken, "/api/admin/links", userCategoryId, userLinkName);

        JsonNode userCategories = getData(getJson("/api/admin/categories", userToken));
        assertTrue(containsCategory(userCategories, userCategoryName));
        assertFalse(containsCategory(userCategories, adminCategoryName));

        JsonNode userLinks = getData(getJson("/api/admin/links?categoryId=" + userCategoryId, userToken));
        assertTrue(containsLink(userLinks, userLinkName));
        assertFalse(containsLink(userLinks, adminLinkName));

        MvcResult publicNavResult = mockMvc.perform(get("/api/public/nav"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=300"))
                .andReturn();
        JsonNode publicNav = getData(readJson(publicNavResult));
        assertTrue(containsCategory(publicNav, adminCategoryName));
        assertFalse(containsCategory(publicNav, userCategoryName));
        assertTrue(containsNestedLink(publicNav, adminLinkName));
        assertFalse(containsNestedLink(publicNav, userLinkName));

        MvcResult signedInNavResult = mockMvc.perform(get("/api/public/nav")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL))
                .andReturn();
        JsonNode signedInNav = getData(readJson(signedInNavResult));
        assertTrue(containsCategory(signedInNav, userCategoryName));
        assertFalse(containsCategory(signedInNav, adminCategoryName));
        assertTrue(containsNestedLink(signedInNav, userLinkName));
        assertFalse(containsNestedLink(signedInNav, adminLinkName));
    }

    private String registerAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
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

    private Long createCategory(String token, String path, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"icon\":\"book\",\"sortOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private Long createLink(String token, String path, Long categoryId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"name\":\"" + name
                                + "\",\"url\":\"https://example.com/" + name
                                + "\",\"description\":\"demo\",\"icon\":\"https://example.com/icon.ico\",\"sortOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result);
    }

    private JsonNode getData(JsonNode json) {
        return json.path("data");
    }

    private boolean containsCategory(JsonNode categories, String name) {
        for (JsonNode category : categories) {
            if (name.equals(category.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLink(JsonNode links, String name) {
        for (JsonNode link : links) {
            if (name.equals(link.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNestedLink(JsonNode categories, String name) {
        for (JsonNode category : categories) {
            if (containsLink(category.path("links"), name)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
