package com.example.website;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.website.module.membership.infrastructure.persistence.RedeemCodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = "app.membership.free-lifetime-enabled=false")
@AutoConfigureMockMvc
class MembershipRedeemIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedeemCodeRepository redeemCodeRepository;

    @Test
    void membershipEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/user/membership"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/user/membership/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"OW-AAAA-BBBB-CCCC-DDDD\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void adminGeneratesSingleUseCodeAndUserRedeemsIt() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String username = "member_" + uniqueSuffix();
        String email = username + "@751152.xyz";
        String userToken = registerAndExtractToken(username, "secret123");

        MvcResult generated = mockMvc.perform(post("/api/admin/redeem-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DAY\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("DAY"))
                .andExpect(jsonPath("$.data.quantity").value(1))
                .andExpect(jsonPath("$.data.codes[0]").isString())
                .andReturn();
        String code = readJson(generated).path("data").path("codes").get(0).asText();

        mockMvc.perform(get("/api/user/membership")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(get("/api/v1/music/play")
                        .header("Authorization", "Bearer " + userToken)
                        .param("source", "qq")
                        .param("id", "song-before-membership"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("需要有效会员才能播放，请先兑换会员卡"));

        mockMvc.perform(post("/api/user/membership/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("兑换成功"))
                .andExpect(jsonPath("$.data.membership.type").value("DAY"))
                .andExpect(jsonPath("$.data.membership.active").value(true));

        mockMvc.perform(get("/api/admin/redeem-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "REDEEMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].code").value(code))
                .andExpect(jsonPath("$.data.items[0].redeemedByUserId").isNumber())
                .andExpect(jsonPath("$.data.items[0].redeemedByUsername").value(username))
                .andExpect(jsonPath("$.data.items[0].redeemedByEmail").value(email));

        mockMvc.perform(post("/api/user/membership/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.toLowerCase() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void adminCanDeleteOnlyRevokedCodes() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String code = generateCode(adminToken, "MONTH");

        MvcResult listed = mockMvc.perform(get("/api/admin/redeem-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "UNUSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].code").value(code))
                .andReturn();
        long codeId = readJson(listed).path("data").path("items").get(0).path("id").asLong();

        mockMvc.perform(delete("/api/admin/redeem-codes/{id}", codeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(409));

        mockMvc.perform(post("/api/admin/redeem-codes/{id}/revoke", codeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(delete("/api/admin/redeem-codes/{id}", codeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertFalse(redeemCodeRepository.existsById(codeId));
    }

    @Test
    void permanentMembershipCannotBeDowngradedByRedeemingAnotherCard() throws Exception {
        String adminToken = loginAndExtractToken("admin", "admin123");
        String userToken = registerAndExtractToken("permanent_" + uniqueSuffix(), "secret123");
        String permanentCode = generateCode(adminToken, "LIFETIME");
        String dayCode = generateCode(adminToken, "DAY");

        redeem(userToken, permanentCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership.lifetime").value(true))
                .andExpect(jsonPath("$.data.membership.remainingSeconds").doesNotExist());

        redeem(userToken, dayCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(409));
    }

    private String generateCode(String adminToken, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/redeem-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("codes").get(0).asText();
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/user/membership/redeem")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private String registerAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\""
                                + username + "@751152.xyz\",\"password\":\"" + password + "\"}"))
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

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
