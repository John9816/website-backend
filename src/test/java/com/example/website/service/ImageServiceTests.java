package com.example.website.service;

import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageEditRequest;
import com.example.website.dto.ImageGenerationsResponse;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageServiceTests {

    private static final String BASE_URL = "https://api.67.si/v1/chat/completions";
    private static final String IMAGES_URL = "https://api.67.si/v1/images/generations";
    private static final String EDITS_URL = "https://api.67.si/v1/images/edits";
    private static final String API_KEY = "test-key";
    private static final String CHAT_MODEL = "grok-imagine-image-lite";
    private static final String GPT_IMAGE_MODEL = "gpt-image-2";
    private static final String DATA_URL = "data:image/png;base64,QUJDRA==";

    @Mock
    private SysConfigService configService;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    @Mock
    private Call retryCall;

    private final Executor executor = Executors.newSingleThreadExecutor();

    private ImageService imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageService(configService, okHttpClient, new ObjectMapper(), executor);
    }

    @Test
    void generateExtractsInlineDataUrlFromChatResponse() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(CHAT_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse(BASE_URL,
                "{\"choices\":[{\"message\":{\"content\":\"![image_1](" + DATA_URL + ")\"}}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");

        ImageGenerationsResponse resp = imageService.generate(req);

        assertEquals(CHAT_MODEL, resp.getModel());
        assertNotNull(resp.getCreated());
        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().size());
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
    }

    @Test
    void generateEmbedsSizeInChatPrompt() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(CHAT_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse(BASE_URL,
                "{\"choices\":[{\"message\":{\"content\":\"![image_1](" + DATA_URL + ")\"}}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");
        req.setSize("1792x1024");

        ImageGenerationsResponse resp = imageService.generate(req);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();
        assertTrue(body.contains("1792×1024 test prompt"));
        assertEquals(CHAT_MODEL, resp.getModel());
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
    }

    @Test
    void generateReroutesGptImageChatConfigToImagesEndpoint() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(GPT_IMAGE_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse(IMAGES_URL,
                "{\"data\":[{\"b64_json\":\"QUJDRA==\"}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");

        ImageGenerationsResponse resp = imageService.generate(req);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        assertTrue(requestCaptor.getValue().url().toString().endsWith("/v1/images/generations"));
        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();
        assertTrue(body.contains("\"prompt\":\"test prompt\""));
        assertTrue(body.contains("\"n\":1"));
        assertFalse(body.contains("\"size\":"));
        assertEquals(GPT_IMAGE_MODEL, resp.getModel());
        assertNotNull(resp.getCreated());
        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().size());
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
    }

    @Test
    void generateSendsSizeAndNWhenRequested() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(GPT_IMAGE_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse(IMAGES_URL,
                "{\"data\":[{\"url\":\"https://cdn.example.com/img1.png\"},{\"url\":\"https://cdn.example.com/img2.png\"}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");
        req.setSize("1792x1024");
        req.setN(2);

        ImageGenerationsResponse resp = imageService.generate(req);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();
        assertTrue(body.contains("\"size\":\"1792x1024\""));
        assertTrue(body.contains("\"n\":2"));
        assertEquals(GPT_IMAGE_MODEL, resp.getModel());
        assertEquals(2, resp.getData().size());
        assertEquals("https://cdn.example.com/img1.png", resp.getData().get(0).getUrl());
        assertEquals("https://cdn.example.com/img2.png", resp.getData().get(1).getUrl());
    }

    @Test
    void generateRetriesImagesEndpointWithMinimalBodyWhenPrimaryReturnsNoImage() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(GPT_IMAGE_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call, retryCall);
        when(call.execute()).thenReturn(okResponse(IMAGES_URL, "{\"data\":[]}"));
        when(retryCall.execute()).thenReturn(okResponse(IMAGES_URL,
                "{\"data\":[{\"b64_json\":\"QUJDRA==\"}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");

        ImageGenerationsResponse resp = imageService.generate(req);

        assertEquals(GPT_IMAGE_MODEL, resp.getModel());
        assertEquals(1, resp.getData().size());
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
    }

    @Test
    void generateRetriesImagesEndpointWithMinimalBodyWhenSizeRequestFails() throws IOException {
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(GPT_IMAGE_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call, retryCall);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url(IMAGES_URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("Bad Request")
                .body(ResponseBody.create("{\"error\":{\"message\":\"unsupported size\"}}",
                        MediaType.get("application/json; charset=utf-8")))
                .build());
        when(retryCall.execute()).thenReturn(okResponse(IMAGES_URL,
                "{\"data\":[{\"b64_json\":\"QUJDRA==\"}]}"));

        ImageGenerateRequest req = new ImageGenerateRequest();
        req.setPrompt("test prompt");
        req.setSize("1792x1024");

        ImageGenerationsResponse resp = imageService.generate(req);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient, org.mockito.Mockito.times(2)).newCall(requestCaptor.capture());
        Buffer retryBuffer = new Buffer();
        requestCaptor.getAllValues().get(1).body().writeTo(retryBuffer);
        String retryBody = retryBuffer.readUtf8();
        assertFalse(retryBody.contains("\"size\":"));
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
    }

    @Test
    void editDerivesImagesEditsEndpointAndSendsMultipartRequest() throws IOException {
        when(configService.getValue(ImageService.CFG_EDIT_BASE_URL)).thenReturn(java.util.Optional.empty());
        when(configService.getValueOrThrow(ImageService.CFG_BASE_URL)).thenReturn(BASE_URL);
        when(configService.getValueOrThrow(ImageService.CFG_API_KEY)).thenReturn(API_KEY);
        when(configService.getValueOrThrow(ImageService.CFG_MODEL)).thenReturn(GPT_IMAGE_MODEL);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse(EDITS_URL,
                "{\"data\":[{\"b64_json\":\"QUJDRA==\",\"revised_prompt\":\"edited prompt\"}]}"));

        ImageEditRequest req = new ImageEditRequest();
        req.setPrompt("add a red hat");
        req.setSize("1024x1024");
        req.setN(1);
        req.setImageBytes(new byte[]{1, 2, 3});
        req.setImageFilename("source.png");
        req.setImageContentType("image/png");
        req.setMaskBytes(new byte[]{4, 5, 6});
        req.setMaskFilename("mask.png");
        req.setMaskContentType("image/png");

        ImageGenerationsResponse resp = imageService.edit(req);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertTrue(request.url().toString().endsWith("/v1/images/edits"));
        assertTrue(request.body().contentType().toString().startsWith("multipart/form-data"));
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        String body = buffer.readUtf8();
        assertTrue(body.contains("name=\"model\""));
        assertTrue(body.contains(GPT_IMAGE_MODEL));
        assertTrue(body.contains("name=\"prompt\""));
        assertTrue(body.contains("add a red hat"));
        assertTrue(body.contains("name=\"size\""));
        assertTrue(body.contains("1024x1024"));
        assertTrue(body.contains("name=\"image\"; filename=\"source.png\""));
        assertTrue(body.contains("name=\"mask\"; filename=\"mask.png\""));
        assertEquals(GPT_IMAGE_MODEL, resp.getModel());
        assertEquals(DATA_URL, resp.getData().get(0).getUrl());
        assertEquals("edited prompt", resp.getData().get(0).getRevisedPrompt());
    }

    private Response okResponse(String requestUrl, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url(requestUrl).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
