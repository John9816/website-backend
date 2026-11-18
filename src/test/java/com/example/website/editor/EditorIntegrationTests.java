package com.example.website.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 学城编辑器迁入 website-backend 的端到端冒烟测试：KV 建表 → JWT 鉴权 → docs CRUD →
 * ACL / 分享 / 回收站，验证整条移植链路可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EditorIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void createKvTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS editor_kv (k VARCHAR(128) PRIMARY KEY, v LONGTEXT NOT NULL)");
        jdbc.execute("DELETE FROM editor_kv");
    }

    private String registerAndToken(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"secret123\",\"email\":\"" + username + "@751152.xyz\"}";
        MvcResult r = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return readTree(r).path("data").path("token").asText();
    }

    private JsonNode readTree(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void anonymousMeReturnsNull() throws Exception {
        mockMvc.perform(get("/api/editor/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/editor/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void authedMeReturnsEditorUser() throws Exception {
        String token = registerAndToken("editor_me_user");
        MvcResult r = mockMvc.perform(get("/api/editor/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = readTree(r).path("data");
        assertTrue(data.has("id"));
        assertEquals("editor_me_user", data.path("username").asText());
        assertEquals("user", data.path("role").asText());
        assertTrue(data.path("canManageMcp").asBoolean() == false);
    }

    @Test
    void docsCrudRoundTrip() throws Exception {
        String token = registerAndToken("editor_doc_owner");
        String auth = "Bearer " + token;

        // 初始列表为空
        MvcResult empty = mockMvc.perform(get("/api/editor/docs").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, readTree(empty).path("data").size());

        // 新建
        String createBody = "{\"title\":\"TestDoc\",\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"HelloEditor\"}]}]}}";
        MvcResult created = mockMvc.perform(post("/api/editor/docs")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode doc = readTree(created).path("data");
        String docId = doc.path("id").asText();
        assertNotNull(docId);
        assertFalse(docId.isEmpty());
        assertEquals("TestDoc", doc.path("title").asText());

        // 列表可见
        MvcResult list = mockMvc.perform(get("/api/editor/docs").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, readTree(list).path("data").size());
        assertEquals("HelloEditor", readTree(list).path("data").get(0).path("excerpt").asText());

        // 读取
        mockMvc.perform(get("/api/editor/docs/" + docId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("TestDoc"))
                .andExpect(jsonPath("$.data.canEdit").value(true));

        // 保存（改标题 + 正文）
        String saveBody = "{\"title\":\"RenamedDoc\",\"content\":{\"type\":\"doc\",\"content\":[]}}";
        mockMvc.perform(put("/api/editor/docs/" + docId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(saveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("RenamedDoc"));

        // 偏好：收藏（PATCH 纯偏好）
        mockMvc.perform(patch("/api/editor/docs/" + docId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true));

        // 删除
        mockMvc.perform(delete("/api/editor/docs/" + docId).header("Authorization", auth))
                .andExpect(status().isOk());

        // 删除后列表为空
        MvcResult after = mockMvc.perform(get("/api/editor/docs").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, readTree(after).path("data").size());

        // 回收站有 1 批
        MvcResult trash = mockMvc.perform(get("/api/editor/trash").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, readTree(trash).path("data").size());
    }

    @Test
    void otherUserCannotSeePrivateDoc() throws Exception {
        String tokenA = registerAndToken("editor_priv_a");
        String tokenB = registerAndToken("editor_priv_b");
        MvcResult created = mockMvc.perform(post("/api/editor/docs")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"PrivateDoc\"}"))
                .andExpect(status().isOk()).andReturn();
        String docId = readTree(created).path("data").path("id").asText();

        // B 看不到 A 的PrivateDoc（列表为空）
        MvcResult listB = mockMvc.perform(get("/api/editor/docs")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, readTree(listB).path("data").size());

        // B 直接按 id 读取得到 403 + needRequest 上下文
        mockMvc.perform(get("/api/editor/docs/" + docId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shareRoundTrip() throws Exception {
        String token = registerAndToken("editor_share_owner");
        String auth = "Bearer " + token;
        MvcResult created = mockMvc.perform(post("/api/editor/docs")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ShareTest\",\"content\":{\"type\":\"doc\",\"content\":[]}}"))
                .andExpect(status().isOk()).andReturn();
        String docId = readTree(created).path("data").path("id").asText();

        MvcResult shared = mockMvc.perform(post("/api/editor/docs/" + docId + "/share")
                        .header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        String shareToken = readTree(shared).path("data").path("token").asText();
        assertFalse(shareToken.isEmpty());

        // 匿名读分享
        mockMvc.perform(get("/api/editor/share/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("ShareTest"));
    }
}
