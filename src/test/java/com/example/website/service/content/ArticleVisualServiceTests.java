package com.example.website.service.content;

import com.example.website.common.BusinessException;
import com.example.website.service.GeneratedImageService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArticleVisualServiceTests {
    @Test
    void validatesTheHostedBytesWhenProviderChangesImageEncoding() throws Exception {
        byte[] original = png(600, 400);
        byte[] hosted = png(640, 480);
        GeneratedImageService storage = mock(GeneratedImageService.class);
        OpenImageSearchService search = mock(OpenImageSearchService.class);
        when(search.searchCommercial(anyString(), anyInt())).thenReturn(Collections.singletonList(
                new OpenImageSearchService.Candidate("https://source.example/image.png",
                        "https://source.example/page", "Image", "Author", "CC0", "Author CC0", "image/png")));
        when(storage.persistImageReference("https://source.example/image.png"))
                .thenReturn("https://host.example/image.png");
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(
                                chain.request().url().host().equals("host.example") ? hosted : original,
                                MediaType.get("image/png"))).build()).build();
        ArticleVisualService service = new ArticleVisualService(null, storage, search, client);

        List<ArticleInlineImage> images = service.create("Topic", "## Section\nText", "fetch");
        assertEquals(1, images.size());
        assertTrue(images.get(0).ready());
        assertEquals("https://host.example/image.png", images.get(0).getSourceUrl());
        assertEquals(640, images.get(0).getWidth());
        assertArrayEquals(hosted, service.readValidatedBytes(images.get(0)));

        doThrow(new BusinessException(502, "Upload failed")).when(storage).persistImageReference(anyString());
        assertFalse(service.create("Topic", "## Section\nText", "fetch").get(0).ready());
    }

    private byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
}
