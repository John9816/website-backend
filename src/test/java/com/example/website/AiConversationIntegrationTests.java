package com.example.website;

import com.example.website.service.AiChatUpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiConversationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiChatUpstreamClient aiChatUpstreamClient;

    @Test
    void aiConversationEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/user/ai/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/user/ai/messages/1/audio"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void conversationsAreScopedToCurrentUser() throws Exception {
        String aliceToken = registerAndExtractToken("ai_scope_alice", "secret123");
        String bobToken = registerAndExtractToken("ai_scope_bob", "secret123");

        Long conversationId = createConversation(aliceToken, "{\"title\":\"Project Copilot\"}");

        JsonNode aliceList = getJson("/api/user/ai/conversations", aliceToken).path("data").path("items");
        assertEquals(1, aliceList.size());
        assertEquals("Project Copilot", aliceList.get(0).path("title").asText());

        JsonNode bobList = getJson("/api/user/ai/conversations", bobToken).path("data").path("items");
        assertEquals(0, bobList.size());

        mockMvc.perform(get("/api/user/ai/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Conversation not found"));
    }

    @Test
    void sendMessageCanRequestOptionalVoicePlaybackAndReturnsReply() throws Exception {
        when(aiChatUpstreamClient.complete(anyString(), anyString(), eq("mimo-v2-omni"), anyList()))
                .thenReturn(new AiChatUpstreamClient.ChatCompletionResult(
                        "mimo-v2-omni",
                        "You can split this into conversations, messages, and model config.",
                        "stop",
                        22,
                        14,
                        36,
                        null
                ));
        when(aiChatUpstreamClient.createSpeech(
                anyString(), anyString(), eq("mimo-v2.5-tts"), anyString(), isNull(), eq("wav"), isNull()
        )).thenReturn(new AiChatUpstreamClient.ChatAudioResult(
                        "audio-test-1",
                        "UklGRgAAAAA=",
                        "audio/wav",
                        null,
                        null
                ));

        String token = registerAndExtractToken("ai_chat_user", "secret123");
        String otherToken = registerAndExtractToken("ai_chat_other_user", "secret123");
        Long conversationId = createConversation(token, "{\"model\":\"mimo-v2-flash\"}");

        JsonNode models = getJson("/api/user/ai/models", token).path("data");
        assertEquals("mimo-v2.5-pro", models.get(0).path("model").asText());
        assertTrue(models.get(0).path("defaultModel").asBoolean());
        assertTrue(containsText(models.get(0).path("capabilities"), "text_chat"));
        assertEquals("mimo-v2.5-tts", models.get(2).path("model").asText());
        assertTrue(containsText(models.get(2).path("capabilities"), "audio_output"));
        assertEquals("mimo-v2-omni", models.get(7).path("model").asText());
        assertTrue(containsText(models.get(7).path("capabilities"), "audio_input"));
        assertTrue(!containsText(models.get(7).path("capabilities"), "audio_output"));

        mockMvc.perform(get("/api/user/ai/models")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].model").value("mimo-v2.5-pro"))
                .andExpect(jsonPath("$.data[0].defaultModel").value(true))
                .andExpect(jsonPath("$.data[7].model").value("mimo-v2-omni"));

        MvcResult sendResult = mockMvc.perform(post("/api/user/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Design API conversation flow\",\"model\":\"mimo-v2-omni\",\"responseAudio\":true,\"ttsModel\":\"mimo-v2.5-tts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conversation.title").value("Design API conversation flow"))
                .andExpect(jsonPath("$.data.conversation.model").value("mimo-v2-omni"))
                .andExpect(jsonPath("$.data.conversation.lastMessagePreview")
                        .value("You can split this into conversations, messages, and model config."))
                .andExpect(jsonPath("$.data.userMessage.model").value("mimo-v2-omni"))
                .andExpect(jsonPath("$.data.userMessage.role").value("user"))
                .andExpect(jsonPath("$.data.assistantMessage.role").value("assistant"))
                .andExpect(jsonPath("$.data.assistantMessage.model").value("mimo-v2-omni"))
                .andExpect(jsonPath("$.data.assistantMessage.audioAvailable").value(true))
                .andExpect(jsonPath("$.data.assistantMessage.audioMimeType").value("audio/wav"))
                .andExpect(jsonPath("$.data.assistantMessage.audioModel").value("mimo-v2.5-tts"))
                .andExpect(jsonPath("$.data.assistantMessage.totalTokens").value(36))
                .andReturn();

        JsonNode reply = readJson(sendResult).path("data");
        long assistantMessageId = reply.path("assistantMessage").path("id").asLong();
        assertTrue(assistantMessageId > 0);
        assertEquals("/api/user/ai/messages/" + assistantMessageId + "/audio",
                reply.path("assistantMessage").path("audioUrl").asText());

        MvcResult audioResult = mockMvc.perform(get("/api/user/ai/messages/" + assistantMessageId + "/audio")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(audioResult.getResponse().getContentType() != null);
        assertTrue(audioResult.getResponse().getContentType().startsWith("audio/"));
        assertTrue(audioResult.getResponse().getContentAsByteArray().length > 0);

        mockMvc.perform(get("/api/user/ai/messages/" + assistantMessageId + "/audio")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/api/user/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].role").value("user"))
                .andExpect(jsonPath("$.data.items[0].content").value("Design API conversation flow"))
                .andExpect(jsonPath("$.data.items[1].role").value("assistant"))
                .andExpect(jsonPath("$.data.items[0].model").value("mimo-v2-omni"))
                .andExpect(jsonPath("$.data.items[1].model").value("mimo-v2-omni"))
                .andExpect(jsonPath("$.data.items[1].audioAvailable").value(true));

        verify(aiChatUpstreamClient).complete(
                eq("https://fufu.iqach.top/v1"),
                eq(""),
                eq("mimo-v2-omni"),
                anyList()
        );
        verify(aiChatUpstreamClient).createSpeech(
                eq("https://fufu.iqach.top/v1"),
                eq(""),
                eq("mimo-v2.5-tts"),
                eq("You can split this into conversations, messages, and model config."),
                isNull(),
                eq("wav"),
                isNull()
        );

        JsonNode conversation = getJson("/api/user/ai/conversations/" + conversationId, token).path("data");
        assertTrue(conversation.path("updatedAt").asText().length() > 0);
    }

    @Test
    void audioInputIsForwardedToCompatibleModelAndStoredForPlayback() throws Exception {
        when(aiChatUpstreamClient.complete(anyString(), anyString(), eq("mimo-v2.5"), anyList()))
                .thenReturn(new AiChatUpstreamClient.ChatCompletionResult(
                        "mimo-v2.5",
                        "This audio sounds like a short greeting.",
                        "stop",
                        18,
                        9,
                        27,
                        null
                ));

        String token = registerAndExtractToken("ai_audio_input_user", "secret123");
        Long conversationId = createConversation(token, "{\"title\":\"Voice Notes\"}");

        MvcResult sendResult = mockMvc.perform(post("/api/user/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"mimo-v2.5\",\"inputAudioData\":\"data:audio/wav;base64,UklGRgAAAAA=\",\"content\":\"请概括音频内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userMessage.audioAvailable").value(true))
                .andExpect(jsonPath("$.data.userMessage.audioMimeType").value("audio/wav"))
                .andExpect(jsonPath("$.data.userMessage.model").value("mimo-v2.5"))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("This audio sounds like a short greeting."))
                .andReturn();

        JsonNode reply = readJson(sendResult).path("data");
        long userMessageId = reply.path("userMessage").path("id").asLong();
        assertEquals("/api/user/ai/messages/" + userMessageId + "/audio",
                reply.path("userMessage").path("audioUrl").asText());

        MvcResult audioResult = mockMvc.perform(get("/api/user/ai/messages/" + userMessageId + "/audio")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("audio/wav", audioResult.getResponse().getContentType());
        assertTrue(audioResult.getResponse().getContentAsByteArray().length > 0);

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiChatUpstreamClient).complete(
                eq("https://fufu.iqach.top/v1"),
                eq(""),
                eq("mimo-v2.5"),
                messagesCaptor.capture()
        );

        @SuppressWarnings("unchecked")
        List<AiChatUpstreamClient.ChatMessage> upstreamMessages = (List<AiChatUpstreamClient.ChatMessage>) messagesCaptor.getValue();
        assertEquals(1, upstreamMessages.size());
        Object content = upstreamMessages.get(0).getContent();
        assertTrue(content instanceof List);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content;
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).get("type"));
        assertEquals("请概括音频内容", parts.get(0).get("text"));
        assertEquals("input_audio", parts.get(1).get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inputAudio = (Map<String, Object>) parts.get(1).get("input_audio");
        assertEquals("data:audio/wav;base64,UklGRgAAAAA=", inputAudio.get("data"));
    }

    @Test
    void voicesEndpointReturnsConfiguredOptions() throws Exception {
        String token = registerAndExtractToken("ai_voices_user", "secret123");

        mockMvc.perform(get("/api/user/ai/voices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        JsonNode voices = getJson("/api/user/ai/voices", token).path("data");
        assertTrue(voices.isArray());
        assertTrue(voices.size() >= 2);
        assertEquals("alloy", voices.get(0).path("id").asText());
        assertEquals("Alloy", voices.get(0).path("label").asText());
    }

    @Test
    void standaloneTtsEndpointReturnsAudioBytes() throws Exception {
        when(aiChatUpstreamClient.createSpeech(
                anyString(), anyString(), eq("mimo-v2.5-tts"), eq("Hello world"), eq("alloy"), eq("wav"), isNull()
        )).thenReturn(new AiChatUpstreamClient.ChatAudioResult(
                        "tts-standalone-1",
                        "UklGRgAAAAA=",
                        "audio/wav",
                        null,
                        null
                ));

        String token = registerAndExtractToken("ai_tts_user", "secret123");

        mockMvc.perform(post("/api/user/ai/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello world\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/user/ai/tts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());

        MvcResult result = mockMvc.perform(post("/api/user/ai/tts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello world\",\"ttsModel\":\"mimo-v2.5-tts\",\"ttsVoice\":\"alloy\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("audio/wav", result.getResponse().getContentType());
        assertTrue(result.getResponse().getContentAsByteArray().length > 0);

        verify(aiChatUpstreamClient).createSpeech(
                eq("https://fufu.iqach.top/v1"),
                eq(""),
                eq("mimo-v2.5-tts"),
                eq("Hello world"),
                eq("alloy"),
                eq("wav"),
                isNull()
        );

        mockMvc.perform(post("/api/user/ai/tts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\",\"ttsModel\":\"mimo-v2.5-pro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void regenerateMessageAudioReplacesStoredAudioForAssistantMessage() throws Exception {
        when(aiChatUpstreamClient.complete(anyString(), anyString(), eq("mimo-v2-omni"), anyList()))
                .thenReturn(new AiChatUpstreamClient.ChatCompletionResult(
                        "mimo-v2-omni",
                        "Initial assistant reply.",
                        "stop",
                        5,
                        5,
                        10,
                        null
                ));
        when(aiChatUpstreamClient.createSpeech(
                anyString(), anyString(), eq("mimo-v2.5-tts"), eq("Initial assistant reply."), eq("nova"), eq("wav"), isNull()
        )).thenReturn(new AiChatUpstreamClient.ChatAudioResult(
                        "tts-regen-2",
                        "UklGRkVHRU4=",
                        "audio/wav",
                        null,
                        null
                ));

        String token = registerAndExtractToken("ai_regen_user", "secret123");
        String otherToken = registerAndExtractToken("ai_regen_other", "secret123");
        Long conversationId = createConversation(token, "{\"model\":\"mimo-v2-omni\"}");

        MvcResult sendResult = mockMvc.perform(post("/api/user/ai/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Generate a short reply\",\"model\":\"mimo-v2-omni\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reply = readJson(sendResult).path("data");
        long assistantId = reply.path("assistantMessage").path("id").asLong();
        long userMessageId = reply.path("userMessage").path("id").asLong();
        assertTrue(reply.path("assistantMessage").path("audioAvailable").asBoolean() == false);

        // Other user must not be able to regenerate audio for this message.
        mockMvc.perform(post("/api/user/ai/messages/" + assistantId + "/audio")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404));

        // Cannot regenerate audio for a user message.
        mockMvc.perform(post("/api/user/ai/messages/" + userMessageId + "/audio")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/user/ai/messages/" + assistantId + "/audio")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttsModel\":\"mimo-v2.5-tts\",\"ttsVoice\":\"nova\",\"ttsFormat\":\"wav\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value((int) assistantId))
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.audioAvailable").value(true))
                .andExpect(jsonPath("$.data.audioMimeType").value("audio/wav"))
                .andExpect(jsonPath("$.data.audioModel").value("mimo-v2.5-tts"))
                .andExpect(jsonPath("$.data.audioUrl").value("/api/user/ai/messages/" + assistantId + "/audio"));

        MvcResult audioResult = mockMvc.perform(get("/api/user/ai/messages/" + assistantId + "/audio")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("audio/wav", audioResult.getResponse().getContentType());
        assertTrue(audioResult.getResponse().getContentAsByteArray().length > 0);
    }

    private String registerAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private Long createConversation(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/ai/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private boolean containsText(JsonNode items, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
