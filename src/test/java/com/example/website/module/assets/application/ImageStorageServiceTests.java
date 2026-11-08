package com.example.website.module.assets.application;

import com.example.website.common.BusinessException;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.assets.infrastructure.persistence.ImageAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImageStorageServiceTests {

    private static final String URL = "https://i.ibb.co/example/image.png";
    private ImageAssetRepository repository;
    private final AtomicReference<Request> request = new AtomicReference<>();
    private String responseJson;
    private int status;
    private ImageStorageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ImageAssetRepository.class);
        when(repository.save(any(ImageAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        responseJson = "{\"code\":0,\"purl\":\"" + URL
                + "\",\"host\":\"ImgBB\",\"delete_url\":\"https://ibb.co/example/delete\",\"source\":\"test\"}";
        status = 200;
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            request.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test")
                    .body(ResponseBody.create(responseJson, MediaType.parse("application/json"))).build();
        }).build();
        service = new ImageStorageService(repository, http, new ObjectMapper(), "https://upload.example/Tc.php");
    }

    @Test
    void uploadsRemoteReferenceAndSavesTheCompleteResponse() {
        ImageAsset asset = service.storeReference("https://upstream.example/image.png?token=a+b&size=2");

        FormBody form = (FormBody) request.get().body();
        assertEquals("POST", request.get().method());
        assertEquals("photo", form.name(0));
        assertEquals("https://upstream.example/image.png?token=a+b&size=2", form.value(0));
        assertEquals("type", form.name(1));
        assertEquals("json", form.value(1));
        assertEquals(URL, asset.getImageUrl());
        assertEquals("ImgBB", asset.getHost());
        assertEquals("https://ibb.co/example/delete", asset.getDeleteUrl());
        assertEquals("test", asset.getSource());
        assertEquals(responseJson, asset.getResponseJson());
        verify(repository).save(asset);
    }

    @Test
    void uploadsBytesAsBase64AndRecordsTheirMetadata() {
        ImageAsset asset = service.storeBytes("test".getBytes(StandardCharsets.UTF_8), "image/png");

        assertEquals("dGVzdA==", ((FormBody) request.get().body()).value(0));
        assertEquals("image/png", asset.getContentType());
        assertEquals(4L, asset.getByteSize());
    }

    @Test
    void acceptsDataUrlsWithoutSendingTheDataPrefix() {
        ImageAsset asset = service.storeReference("data:image/webp;base64,dGVzdA==");

        assertEquals("dGVzdA==", ((FormBody) request.get().body()).value(0));
        assertEquals("image/webp", asset.getContentType());
    }

    @Test
    void reusesAUrlAlreadyRecordedInTheDatabase() {
        ImageAsset existing = new ImageAsset();
        existing.setImageUrl(URL);
        when(repository.findFirstByUrlHash(anyString())).thenReturn(Optional.of(existing));

        assertSame(existing, service.storeReference(URL));
        assertNull(request.get());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":1,\"purl\":\"https://i.ibb.co/example/image.png\"}",
            "{\"purl\":\"https://i.ibb.co/example/image.png\"}",
            "{\"code\":0}",
            "{\"code\":0,\"purl\":\"javascript:alert(1)\"}",
            "{\"code\":0,\"purl\":\"/relative.png\"}",
            "<html>unavailable</html>", "null"
    })
    void rejectsFailedOrMalformedResponsesWithoutSaving(String json) {
        responseJson = json;

        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.storeReference("https://upstream.example/image.png")).getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsHttpErrorsWithoutSaving() {
        status = 503;
        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.storeReference("https://upstream.example/image.png")).getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNetworkErrorsWithoutSaving() {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            throw new IOException("timeout");
        }).build();
        service = new ImageStorageService(repository, http, new ObjectMapper(), "https://upload.example/Tc.php");

        assertEquals(502, assertThrows(BusinessException.class,
                () -> service.storeReference("https://upstream.example/image.png")).getCode());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/unknown/local.png", "file:///tmp/image.png", "data:image/png;base64,broken!"})
    void rejectsInvalidInputWithoutUploading(String reference) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.storeReference(reference)).getCode());
        assertNull(request.get());
        verify(repository, never()).save(any());
    }
}
