package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.example.website.module.music.api.dto.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import javax.xml.stream.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Optional lyrics only: never delays or changes the audio resolution path. */
@Component
public class TtmlLyricsClient {
    private static final int LIMIT = 1024 * 1024;
    private final OkHttpClient http;
    private final Cache<String, TtmlLyricsView> cache = Caffeine.newBuilder().maximumWeight(16 * LIMIT)
            .weigher((String k, TtmlLyricsView v) -> v.getContent() == null ? 128 : v.getContent().length() * 2)
            .expireAfterWrite(5, TimeUnit.MINUTES).build();
    private static final TtmlLyricsView MISSING = new TtmlLyricsView(false, null, null);
    public TtmlLyricsClient(@Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient http) {
        this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .callTimeout(4, TimeUnit.SECONDS).build();
    }
    public TtmlLyricsView fetch(MusicSource source, String id) {
        if (source != MusicSource.NETEASE && source != MusicSource.QQ) return MISSING;
        if (id == null || !(source == MusicSource.NETEASE ? id.matches("[0-9]{1,20}") : id.matches("[A-Za-z0-9_-]{1,64}")))
            throw new BusinessException(400, "Invalid TTML song ID");
        String key = (source == MusicSource.NETEASE ? "wy" : "qq") + "/" + id;
        return cache.get(key, k -> {
            try (Response r = http.newCall(new Request.Builder().url("https://lyrics.sayqz.com/v1/ttml/" + k).build()).execute()) {
                if (!r.isSuccessful() || r.body() == null || r.body().contentLength() > LIMIT) return MISSING;
                r.body().source().request(LIMIT + 1L);
        byte[] bytes = r.body().source().readByteArray(Math.min(LIMIT + 1L, r.body().source().buffer().size()));
                if (bytes.length > LIMIT) return MISSING;
                String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return valid(xml) ? new TtmlLyricsView(true, xml, "AMLL / lyrics.sayqz.com") : MISSING;
            } catch (IOException | RuntimeException ex) { return MISSING; }
        });
    }
    static boolean valid(String xml) {
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return false;
        XMLStreamReader r = null;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            f.setXMLResolver((a,b,c,d) -> { throw new XMLStreamException("External resources disabled"); });
            r = f.createXMLStreamReader(new StringReader(xml));
            int depth = 0, nodes = 0, lines = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) return false;
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 32 || ++nodes > 30000 || r.getAttributeCount() > 32) return false;
                    if (depth == 1 && (!"tt".equals(r.getLocalName()) || !"http://www.w3.org/ns/ttml".equals(r.getNamespaceURI()))) return false;
                    if ("p".equals(r.getLocalName()) && ++lines > 3000) return false;
                } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
            }
            return lines > 0;
        } catch (XMLStreamException | IllegalArgumentException ex) { return false; }
        finally { if (r != null) try { r.close(); } catch (XMLStreamException ignored) {} }
    }
}
