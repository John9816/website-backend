package com.example.website;

import com.example.website.module.identity.domain.EmailVerificationCode;
import com.example.website.module.identity.domain.EmailVerificationPurpose;
import com.example.website.module.identity.domain.User;
import com.example.website.module.identity.infrastructure.persistence.EmailVerificationCodeRepository;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import com.example.website.module.identity.application.CloudMailClient;
import com.example.website.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.registration.email-verification.required=true")
class EmailVerificationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailVerificationCodeRepository codeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private CloudMailClient cloudMailClient;

    @Test
    void sendsCodeFromConfiguredSender() throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"verify_send@751152.xyz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));

        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cloudMailClient).sendRegistrationCode(eq("verify_send@751152.xyz"),
                codeCaptor.capture(), eq(10L));
        EmailVerificationCode stored = codeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(
                        "verify_send@751152.xyz", EmailVerificationPurpose.REGISTER)
                .orElseThrow(AssertionError::new);
        assertNotEquals(sha256(codeCaptor.getValue()), stored.getCodeHash());
        assertEquals(EmailVerificationPurpose.REGISTER, stored.getPurpose());
    }

    @Test
    void registeredEmailGetsGenericSuccessWithoutSendingMail() throws Exception {
        User user = new User();
        user.setUsername("existing_email_user");
        user.setEmail("existing_email@751152.xyz");
        user.setPassword("not-used-in-this-test");
        user.setRole(User.ROLE_USER);
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"existing_email@751152.xyz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));

        verify(cloudMailClient, never()).sendRegistrationCode(
                eq("existing_email@751152.xyz"), anyString(), eq(10L));
    }

    @Test
    void verifiedEmailCanRegisterAndCodeIsConsumed() throws Exception {
        String email = "verify_register@751152.xyz";
        EmailVerificationCode code = new EmailVerificationCode();
        code.setEmail(email);
        code.setCodeHash(sha256("123456"));
        code.setCreatedAt(LocalDateTime.now());
        code.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        code.setAttempts(0);
        codeRepository.save(code);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"verified_user\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("verified_user"));

        EmailVerificationCode consumed = codeRepository.findById(code.getId()).orElseThrow(AssertionError::new);
        assertNotNull(consumed.getUsedAt());
        org.junit.jupiter.api.Assertions.assertTrue(userRepository.existsByEmail(email));
    }

    @Test
    void invalidCodeCannotRegister() throws Exception {
        String email = "verify_invalid@751152.xyz";
        EmailVerificationCode code = new EmailVerificationCode();
        code.setEmail(email);
        code.setCodeHash(sha256("123456"));
        code.setCreatedAt(LocalDateTime.now());
        code.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        code.setAttempts(0);
        codeRepository.save(code);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"invalid_code_user\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\",\"verificationCode\":\"654321\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        EmailVerificationCode attempted = codeRepository.findById(code.getId()).orElseThrow(AssertionError::new);
        assertEquals(1, attempted.getAttempts());
    }

    @Test
    void maxAttemptsBlocksFurtherRegistrationTries() throws Exception {
        String email = "verify_locked@751152.xyz";
        EmailVerificationCode code = verificationCode(email, "123456");
        code.setAttempts(5);
        codeRepository.save(code);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"locked_code_user\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void expiredCodeCannotRegister() throws Exception {
        String email = "verify_expired@751152.xyz";
        EmailVerificationCode code = verificationCode(email, "123456");
        code.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        codeRepository.save(code);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"expired_code_user\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void resendWithinCooldownIsRejected() throws Exception {
        String payload = "{\"email\":\"verify_resend@751152.xyz\"}";
        mockMvc.perform(post("/api/auth/email-code")
                        .header("CF-Connecting-IP", "198.51.100.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/email-code")
                        .header("CF-Connecting-IP", "198.51.100.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void codeRecordCommitsBeforeMailFailure() throws Exception {
        String email = "verify_mail_failure@751152.xyz";
        doThrow(new BusinessException(503, "mail unavailable"))
                .when(cloudMailClient).sendRegistrationCode(eq(email), anyString(), eq(10L));

        mockMvc.perform(post("/api/auth/email-code")
                        .header("CF-Connecting-IP", "198.51.100.21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        assertNotNull(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                email, EmailVerificationPurpose.REGISTER).orElse(null));
    }

    @Test
    void sendsPasswordResetCodeOnlyForExistingEmail() throws Exception {
        String email = "reset_send@751152.xyz";
        saveUser("reset_send_user", email, "old-secret");

        mockMvc.perform(post("/api/auth/password-reset/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));

        org.mockito.ArgumentCaptor<String> codeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cloudMailClient).sendPasswordResetCode(eq(email), codeCaptor.capture(), eq(10L));
        EmailVerificationCode stored = codeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(
                        email, EmailVerificationPurpose.PASSWORD_RESET)
                .orElseThrow(AssertionError::new);
        assertEquals(EmailVerificationPurpose.PASSWORD_RESET, stored.getPurpose());
        assertNotEquals(sha256(codeCaptor.getValue()), stored.getCodeHash());
    }

    @Test
    void unknownResetEmailGetsGenericSuccessWithoutSendingMail() throws Exception {
        String email = "reset_unknown@751152.xyz";

        mockMvc.perform(post("/api/auth/password-reset/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));

        verify(cloudMailClient, never()).sendPasswordResetCode(
                eq(email), anyString(), eq(10L));
    }

    @Test
    void passwordResetChangesPasswordAndRevokesExistingSessions() throws Exception {
        String username = "reset_session_user";
        String email = "reset_session@751152.xyz";
        String oldPassword = "old-secret";
        String newPassword = "new-secret";
        saveUser(username, email, oldPassword);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, oldPassword)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginData = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("data");
        String accessToken = loginData.path("token").asText();
        String refreshToken = loginData.path("refreshToken").asText();

        EmailVerificationCode code = verificationCode(
                email, "123456", EmailVerificationPurpose.PASSWORD_RESET);
        codeRepository.save(code);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"verificationCode\":\"123456\",\"newPassword\":\""
                                + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        EmailVerificationCode consumed = codeRepository.findById(code.getId())
                .orElseThrow(AssertionError::new);
        assertNotNull(consumed.getUsedAt());
        User updated = userRepository.findByUsername(username).orElseThrow(AssertionError::new);
        assertTrue(passwordEncoder.matches(newPassword, updated.getPassword()));
        assertEquals(1, updated.getAuthVersion());

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, oldPassword)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("email", email)
                                .put("password", newPassword).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void registrationCodeCannotResetPassword() throws Exception {
        String username = "reset_wrong_purpose_user";
        String email = "reset_wrong_purpose@751152.xyz";
        String oldPassword = "old-secret";
        saveUser(username, email, oldPassword);
        EmailVerificationCode registrationCode = verificationCode(email, "123456");
        codeRepository.save(registrationCode);

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"verificationCode\":\"123456\",\"newPassword\":\"new-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        User unchanged = userRepository.findByUsername(username).orElseThrow(AssertionError::new);
        assertTrue(passwordEncoder.matches(oldPassword, unchanged.getPassword()));
    }

    private EmailVerificationCode verificationCode(String email, String rawCode) throws Exception {
        return verificationCode(email, rawCode, EmailVerificationPurpose.REGISTER);
    }

    private EmailVerificationCode verificationCode(String email,
                                                    String rawCode,
                                                    EmailVerificationPurpose purpose) throws Exception {
        EmailVerificationCode code = new EmailVerificationCode();
        code.setEmail(email);
        code.setPurpose(purpose);
        code.setCodeHash(sha256(rawCode));
        code.setCreatedAt(LocalDateTime.now());
        code.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        code.setAttempts(0);
        return code;
    }

    private User saveUser(String username, String email, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(User.ROLE_USER);
        return userRepository.save(user);
    }

    private String loginBody(String identifier, String password) {
        return "{\"username\":\"" + identifier + "\",\"password\":\"" + password + "\"}";
    }

    private String sha256(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
