package com.example.website.service.kb;

import com.example.website.common.BusinessException;
import com.example.website.entity.ImageAsset;
import com.example.website.service.ImageStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KbImageDocumentNormalizerTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesJsonAndHtmlImagesAndPreservesOtherContent() throws Exception {
        ImageStorageService storage = mock(ImageStorageService.class);
        ImageAsset asset = new ImageAsset();
        asset.setImageUrl("https://cdn.example/hosted.png");
        when(storage.storeReference("https://upstream.example/image.png")).thenReturn(asset);
        when(storage.storeReference("data:image/png;base64,aGVsbG8=")).thenReturn(asset);

        KbImageDocumentNormalizer.Result result = new KbImageDocumentNormalizer(storage, mapper).normalize(
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"keep\"}]},{\"type\":\"image\",\"attrs\":{\"src\":\"https://upstream.example/image.png\",\"alt\":\"cover\"}}]}",
                "<p>keep this</p><img src=\"data:image/png;base64,aGVsbG8=\" alt=\"inline\">");

        JsonNode json = mapper.readTree(result.getContentJson());
        assertEquals("keep", json.at("/content/0/content/0/text").asText());
        assertEquals("cover", json.at("/content/1/attrs/alt").asText());
        assertEquals("https://cdn.example/hosted.png", json.at("/content/1/attrs/src").asText());
        assertEquals("https://cdn.example/hosted.png", org.jsoup.Jsoup.parseBodyFragment(result.getContentHtml())
                .selectFirst("img").attr("src"));
        verify(storage).storeReference("https://upstream.example/image.png");
        verify(storage).storeReference("data:image/png;base64,aGVsbG8=");
    }

    @Test
    void leavesLegacyKbAssetsInPlaceAndDoesNotUploadThem() {
        ImageStorageService storage = mock(ImageStorageService.class);
        KbImageDocumentNormalizer.Result result = new KbImageDocumentNormalizer(storage, mapper).normalize(
                "{\"type\":\"image\",\"attrs\":{\"src\":\"/api/v1/kb/assets/legacy.png\"}}",
                "<img src=\"/api/v1/kb/assets/legacy.png\">");

        assertEquals("/api/v1/kb/assets/legacy.png", readJson(result.getContentJson())
                .path("attrs").path("src").asText());
        assertEquals("/api/v1/kb/assets/legacy.png", org.jsoup.Jsoup.parseBodyFragment(result.getContentHtml())
                .selectFirst("img").attr("src"));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsDocumentWhenAnyImageCannotBeHosted() {
        ImageStorageService storage = mock(ImageStorageService.class);
        when(storage.storeReference(anyString())).thenThrow(new BusinessException(502, "Image upload failed"));

        assertThrows(BusinessException.class, () -> new KbImageDocumentNormalizer(storage, mapper).normalize(
                "{\"type\":\"image\",\"attrs\":{\"src\":\"https://upstream.example/image.png\"}}", null));
    }

    @Test
    void deduplicatesMatchingSourcesAcrossJsonAndHtml() {
        ImageStorageService storage = mock(ImageStorageService.class);
        ImageAsset asset = new ImageAsset();
        asset.setImageUrl("https://cdn.example/one.png");
        when(storage.storeReference("https://upstream.example/one.png")).thenReturn(asset);

        new KbImageDocumentNormalizer(storage, mapper).normalize(
                "{\"type\":\"image\",\"attrs\":{\"src\":\"https://upstream.example/one.png\"}}",
                "<img src=\"https://upstream.example/one.png\">");

        verify(storage, times(1)).storeReference("https://upstream.example/one.png");
    }

    @Test
    void rejectsInvalidJsonBeforeCallingStorage() {
        ImageStorageService storage = mock(ImageStorageService.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> new KbImageDocumentNormalizer(storage, mapper).normalize("{invalid", null));

        assertEquals(400, error.getCode());
        verifyNoInteractions(storage);
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
