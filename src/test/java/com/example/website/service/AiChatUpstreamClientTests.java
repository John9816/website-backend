package com.example.website.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatUpstreamClientTests {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    private AiChatUpstreamClient client;

    @BeforeEach
    void setUp() {
        client = new AiChatUpstreamClient(okHttpClient, okHttpClient, new ObjectMapper());
    }

    @Test
    void createSpeechPostsToAudioSpeechAndReturnsBase64Audio() throws IOException {
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url("https://api.example.com/v1/audio/speech").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(audioBytes, MediaType.get("audio/mpeg")))
                .build());

        AiChatUpstreamClient.ChatAudioResult result = client.createSpeech(
                "https://api.example.com/v1",
                "test-key",
                "tts-1",
                "Hello world",
                "alloy",
                "mp3",
                1.1
        );

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertEquals("https://api.example.com/v1/audio/speech", request.url().toString());
        assertEquals("Bearer test-key", request.header("Authorization"));

        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        String body = buffer.readUtf8();
        assertTrue(body.contains("\"model\":\"tts-1\""));
        assertTrue(body.contains("\"input\":\"Hello world\""));
        assertTrue(body.contains("\"voice\":\"alloy\""));
        assertTrue(body.contains("\"response_format\":\"mp3\""));
        assertTrue(body.contains("\"speed\":1.1"));

        assertEquals(Base64.getEncoder().encodeToString(audioBytes), result.getData());
        assertEquals("audio/mpeg", result.getMimeType());
    }
}
