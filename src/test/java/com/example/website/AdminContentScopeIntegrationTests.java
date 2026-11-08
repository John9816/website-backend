package com.example.website;

import com.example.website.common.BusinessException;
import com.example.website.module.content.domain.ContentArticle;
import com.example.website.module.identity.domain.User;
import com.example.website.module.content.infrastructure.persistence.ContentArticleRepository;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import com.example.website.module.content.application.ContentArticleService;
import com.example.website.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the admin content console is site-wide while the existing
 * service-level owner predicate remains intact for ordinary callers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminContentScopeIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ContentArticleRepository articles;
    @Autowired private ContentArticleService contentArticleService;

    @Test
    void adminCanReadLegacyRegularUserArticleButUserScopeStaysIsolated() throws Exception {
        User admin = users.findByUsername("admin").orElseThrow(AssertionError::new);
        User owner = newUser("content_scope_owner");
        User other = newUser("content_scope_other");

        ContentArticle article = new ContentArticle();
        article.setUserId(owner.getId());
        article.setTitle("Legacy article owned by a regular user");
        article.setDigest("Legacy digest");
        article.setContentMarkdown("# Legacy article\n\nThis is an existing article that the admin must be able to operate.");
        article.setContentHtml("<h1>Legacy article</h1><p>This is an existing article.</p>");
        article.setStatus(ContentArticle.STATUS_DRAFT);
        ContentArticle saved = articles.saveAndFlush(article);

        String adminToken = bearer(admin);
        mockMvc.perform(get("/api/admin/content/articles")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", hasItem(saved.getId().intValue())));
        mockMvc.perform(get("/api/admin/content/articles/{id}", saved.getId())
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(saved.getId().intValue()))
                .andExpect(jsonPath("$.data.title").value("Legacy article owned by a regular user"));

        // The admin route is role protected even when the regular user owns the row.
        mockMvc.perform(get("/api/admin/content/articles/{id}", saved.getId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());

        // Existing owner-scoped service behavior is unchanged: owner can read its row,
        // another regular user cannot infer or access it.
        assertDoesNotThrow(() -> contentArticleService.get(owner.getId(), saved.getId()));
        assertThrows(BusinessException.class,
                () -> contentArticleService.get(other.getId(), saved.getId()));
    }

    private User newUser(String prefix) {
        User user = new User();
        user.setUsername(prefix + "_" + System.nanoTime());
        user.setEmail(user.getUsername() + "@example.com");
        user.setPassword(encoder.encode("password123"));
        user.setRole(User.ROLE_USER);
        return users.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(
                user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion());
    }
}
