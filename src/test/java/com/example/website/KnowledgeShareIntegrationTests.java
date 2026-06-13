package com.example.website;

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

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeShareIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicShareEndpointReturnsCurrentDocAndOwnersOtherActiveShares() throws Exception {
        String ownerToken = registerAndExtractToken("kb_share_owner", "secret123");
        String otherUserToken = registerAndExtractToken("kb_share_other", "secret123");

        Long ownerSpaceId = createSpace(ownerToken, "owner-space");
        Long otherSpaceId = createSpace(otherUserToken, "other-space");

        Long currentDocId = createDoc(ownerToken, ownerSpaceId, null, "public-doc-a", "summary-a", "<p>A</p>", 10);
        Long siblingDocId = createDoc(ownerToken, ownerSpaceId, currentDocId, "public-doc-b", "summary-b", "<p>B</p>", 20);
        Long disabledDocId = createDoc(ownerToken, ownerSpaceId, null, "private-doc-c", "summary-c", "<p>C</p>", 30);
        Long otherUserDocId = createDoc(otherUserToken, otherSpaceId, null, "other-doc-d", "summary-d", "<p>D</p>", 40);

        String currentShareToken = enableShare(ownerToken, currentDocId, "{}");
        String siblingShareToken = enableShare(ownerToken, siblingDocId, "{}");
        enableShare(ownerToken, disabledDocId, "{\"enabled\":false}");
        enableShare(otherUserToken, otherUserDocId, "{}");

        MvcResult result = mockMvc.perform(get("/api/public/kb/share/" + currentShareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=300"))
                .andExpect(jsonPath("$.data.id").value(currentDocId))
                .andExpect(jsonPath("$.data.token").value(currentShareToken))
                .andExpect(jsonPath("$.data.title").value("public-doc-a"))
                .andExpect(jsonPath("$.data.contentHtml").value("<p>A</p>"))
                .andExpect(jsonPath("$.data.documents", hasSize(2)))
                .andExpect(jsonPath("$.data.documents[0].id").value(currentDocId))
                .andExpect(jsonPath("$.data.documents[0].token").value(currentShareToken))
                .andExpect(jsonPath("$.data.documents[1].id").value(siblingDocId))
                .andExpect(jsonPath("$.data.documents[1].token").value(siblingShareToken))
                .andReturn();

        JsonNode documents = readJson(result).path("data").path("documents");
        assertTrue(containsDoc(documents, currentDocId, currentShareToken));
        assertTrue(containsDoc(documents, siblingDocId, siblingShareToken));
        assertFalse(containsDoc(documents, disabledDocId, null));
        assertFalse(containsDoc(documents, otherUserDocId, null));

        JsonNode currentDoc = findDoc(documents, currentDocId);
        JsonNode siblingDoc = findDoc(documents, siblingDocId);
        assertNotNull(currentDoc);
        assertNotNull(siblingDoc);
        assertFalse(currentDoc.hasNonNull("parentId"));
        assertEquals(10, currentDoc.path("sortOrder").asInt());
        assertEquals(currentDocId, siblingDoc.path("parentId").asLong());
        assertEquals(20, siblingDoc.path("sortOrder").asInt());
    }

    private String registerAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private String registerBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + qqEmail(username)
                + "\",\"password\":\"" + password + "\"}";
    }

    private String qqEmail(String seed) {
        CRC32 crc32 = new CRC32();
        crc32.update(seed.getBytes(StandardCharsets.UTF_8));
        long number = 10000L + (crc32.getValue() % 9999999999L);
        return number + "@qq.com";
    }

    private Long createSpace(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/kb/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"demo\",\"sortOrder\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private Long createDoc(String token, Long spaceId, Long parentId, String title, String summary,
                           String html, int sortOrder) throws Exception {
        String body = "{\"spaceId\":" + spaceId +
                (parentId == null ? "" : ",\"parentId\":" + parentId) +
                ",\"title\":\"" + title +
                "\",\"summary\":\"" + summary +
                "\",\"contentJson\":\"{}\"" +
                ",\"contentHtml\":\"" + html +
                "\",\"sortOrder\":" + sortOrder +
                ",\"status\":\"published\"}";
        MvcResult result = mockMvc.perform(post("/api/user/kb/docs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private String enableShare(String token, Long docId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/kb/docs/" + docId + "/share")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private boolean containsDoc(JsonNode documents, Long docId, String shareToken) {
        for (JsonNode doc : documents) {
            if (doc.path("id").asLong() == docId) {
                if (shareToken == null) {
                    return true;
                }
                return shareToken.equals(doc.path("token").asText());
            }
        }
        return false;
    }

    private JsonNode findDoc(JsonNode documents, Long docId) {
        for (JsonNode doc : documents) {
            if (doc.path("id").asLong() == docId) {
                return doc;
            }
        }
        return null;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
