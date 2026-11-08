package com.example.website;

import com.example.website.module.identity.domain.User;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import com.example.website.module.content.application.ContentAutopilotSlotService;
import com.example.website.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ContentAutomationSecurityIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ContentAutopilotSlotService slots;

    @Test
    void automationUsesJwtUserIdAndOnlyAdminMayControlGlobalWechat() throws Exception {
        User admin = users.findByUsername("admin").orElseThrow(AssertionError::new);
        User regular = users.findByUsername("automation_regular").orElseGet(() -> {
            User user = new User();
            user.setUsername("automation_regular");
            user.setEmail("automation_regular@example.com");
            user.setPassword(encoder.encode("password123"));
            user.setRole(User.ROLE_USER);
            return users.save(user);
        });

        mockMvc.perform(get("/api/content/automation"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/content/automation")
                        .header("Authorization", bearer(regular)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/content/templates")
                        .header("Authorization", bearer(regular)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/content/automation")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\":\"Once\",\"taskType\":\"GENERATE_ARTICLE\","
                                + "\"scheduleType\":\"ONCE\",\"isActive\":false,"
                                + "\"config\":{\"topic\":\"Release notes\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(admin.getId()))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void slotClaimIsUniqueAndSuccessCannotBeClaimedAgain() {
        LocalDateTime slot = LocalDateTime.of(2026, 9, 19, 20, 0);
        LocalDateTime now = slot.plusSeconds(1);
        String first = slots.claim(900101L, slot, now);

        assertNotNull(first);
        assertNull(slots.claim(900101L, slot, now));
        slots.complete(900101L, slot, first, 42L);
        assertEquals(42L, slots.articleId(900101L, slot));
        assertNull(slots.claim(900101L, slot, now.plusMinutes(1)));
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(
                user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion());
    }
}
