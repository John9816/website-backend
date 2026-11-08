package com.example.website.module.content.application;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.example.website.module.images.api.dto.ImageGenerateRequest;
import com.example.website.module.images.api.dto.ImageGenerationsResponse;
import com.example.website.module.images.application.GeneratedImageService;
import com.example.website.module.images.application.ImageService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.hasText;
import static com.example.website.module.content.application.ContentTextUtils.hashText;

@Service
public class ArticleVisualService {

    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final Path CONTENT_ASSET_DIR = Paths.get("uploads", "content-assets");

    private final ImageService imageService;
    private final GeneratedImageService generatedImageService;
    private final OpenImageSearchService openImageSearchService;
    private final OkHttpClient okHttpClient;

    public ArticleVisualService(ImageService imageService,
                                GeneratedImageService generatedImageService,
                                OpenImageSearchService openImageSearchService,
                                @Qualifier(OkHttpConfig.CLIENT_QUICK) OkHttpClient okHttpClient) {
        this.imageService = imageService;
        this.generatedImageService = generatedImageService;
        this.openImageSearchService = openImageSearchService;
        this.okHttpClient = okHttpClient;
    }

    public List<ArticleInlineImage> create(String topic, String markdown, String imageMode) {
        List<String> sections = selectSections(markdown);
        List<ArticleInlineImage> result = new ArrayList<>();
        Set<String> digests = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            String section = sections.get(i);
            String slotId = "inline-" + (i + 1) + "-" + hashText(section).substring(0, 8);
            String prompt = visualPrompt(topic, section);
            ArticleInlineImage image = null;
            if (!"fetch".equalsIgnoreCase(imageMode) && !"none".equalsIgnoreCase(imageMode)) {
                image = generateAi(slotId, section, prompt);
                image = acceptDistinct(image, digests) ? image : null;
            }
            if (image == null && !"none".equalsIgnoreCase(imageMode)) {
                image = findOpenImage(slotId, topic, section, prompt, digests);
            }
            if (image == null) {
                image = ArticleInlineImage.builder()
                        .slotId(slotId)
                        .sectionKey(hashText(section))
                        .sectionTitle(section)
                        .prompt(prompt)
                        .alt(section)
                        .sourceType("open_image")
                        .status("FAILED")
                        .failureReason("No distinct validated AI or commercially reusable open image was available")
                        .build();
            }
            if (image.ready()) {
                digests.add(image.getSha256());
            }
            result.add(image);
        }
        return result;
    }

    public ArticleInlineImage createCoverFallback(String topic, String coverPrompt) {
        String prompt = defaultText(coverPrompt, visualPrompt(topic, "cover"));
        return findOpenImage("cover-" + hashText(prompt).substring(0, 8),
                defaultText(topic, "editorial cover"),
                "cover",
                prompt,
                new HashSet<String>());
    }

    private ArticleInlineImage generateAi(String slotId, String section, String prompt) {
        try {
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setPrompt(prompt);
            request.setN(1);
            request.setSize("1200x675");
            ImageGenerationsResponse response = imageService.generate(request);
            if (response.getData() == null) {
                return null;
            }
            for (ImageGenerationsResponse.ImageDataItem item : response.getData()) {
                String reference = item.getUrl();
                if (!hasText(reference) && hasText(item.getB64Json())) {
                    reference = "data:image/png;base64," + item.getB64Json();
                }
                if (!hasText(reference)) {
                    continue;
                }
                String persisted = generatedImageService.persistImageReference(reference);
                ArticleInlineImage image = buildValidated(
                        slotId, section, prompt, "ai", persisted,
                        null, null, null, null, null);
                if (image != null) {
                    return image;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public String generateCoverImageUrl(String prompt) {
        try {
            ImageGenerateRequest request = new ImageGenerateRequest();
            request.setPrompt(prompt);
            request.setN(1);
            request.setSize("1200x675");
            ImageGenerationsResponse response = imageService.generate(request);
            if (response.getData() != null) {
                for (ImageGenerationsResponse.ImageDataItem item : response.getData()) {
                    if (hasText(item.getUrl())) {
                        return generatedImageService.persistImageReference(item.getUrl());
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public byte[] readCoverBytes(String coverImageUrl) {
        String url = ContentTextUtils.trimToNull(coverImageUrl);
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("/api/v1/content/assets/")) {
                return readLocalFile(CONTENT_ASSET_DIR, url.substring("/api/v1/content/assets/".length()));
            }
            if (url.startsWith("/api/v1/image/file/")) {
                return generatedImageService.readFile(url.substring("/api/v1/image/file/".length()));
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                Request request = new Request.Builder().url(url).get().build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new BusinessException(502, "下载文章封面失败：HTTP " + response.code());
                    }
                    return response.body().bytes();
                }
            }
            return null;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(500, "读取文章封面失败：" + e.getMessage());
        }
    }

    public String coverFilename(String coverImageUrl) {
        String value = defaultText(coverImageUrl, "cover.png");
        int slash = value.lastIndexOf('/');
        String filename = slash >= 0 ? value.substring(slash + 1) : value;
        int query = filename.indexOf('?');
        if (query >= 0) {
            filename = filename.substring(0, query);
        }
        if (!hasText(filename)) {
            return "cover.jpg";
        }
        return filename.contains(".") ? filename : filename + ".jpg";
    }

    public byte[] generateDefaultCoverBytes(String title, String category) {
        int width = 900;
        int height = 500;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(248, 250, 252));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(22, 101, 52));
            graphics.fillRect(0, 0, 18, height);
            graphics.setColor(new Color(15, 23, 42));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            drawWrappedText(graphics, defaultText(title, "早一步信息差"), 72, 130, 760, 68, 3);
            graphics.setColor(new Color(71, 85, 105));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
            drawWrappedText(graphics, defaultText(category, "热点解读"), 74, 360, 760, 38, 1);
            graphics.setColor(new Color(22, 101, 52));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            graphics.drawString("早一步信息差", 72, 430);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "生成默认微信封面失败：" + e.getMessage());
        }
    }

    private byte[] readLocalFile(Path baseDir, String filename) throws IOException {
        if (filename == null || !filename.matches("^[A-Za-z0-9._-]+$")) {
            throw new BusinessException(400, "文章封面文件名不合法");
        }
        Path base = baseDir.normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || !Files.exists(file)) {
            throw new BusinessException(404, "文章封面文件不存在");
        }
        return Files.readAllBytes(file);
    }

    private void drawWrappedText(Graphics2D graphics,
                                 String text,
                                 int x,
                                 int y,
                                 int maxWidth,
                                 int lineHeight,
                                 int maxLines) {
        FontMetrics metrics = graphics.getFontMetrics();
        String normalized = defaultText(text, "").replaceAll("\\s+", " ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            String candidate = line.toString() + character;
            if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0) {
                lines++;
                graphics.drawString(line.toString(), x, y + (lines - 1) * lineHeight);
                if (lines >= maxLines) {
                    return;
                }
                line.setLength(0);
            }
            line.append(character);
        }
        if (line.length() > 0 && lines < maxLines) {
            graphics.drawString(line.toString(), x, y + lines * lineHeight);
        }
    }

    private ArticleInlineImage findOpenImage(String slotId,
                                             String topic,
                                             String section,
                                             String prompt,
                                             Set<String> digests) {
        List<OpenImageSearchService.Candidate> candidates = openImageSearchService.searchCommercial(
                defaultText(topic, "") + " " + section, 5);
        for (OpenImageSearchService.Candidate candidate : candidates) {
            ArticleInlineImage image = buildValidated(
                    slotId, section, prompt, "open_image", candidate.getImageUrl(),
                    candidate.getPageUrl(), candidate.getAuthor(), candidate.getLicense(),
                    candidate.getAttribution(), candidate.getMimeType());
            if (acceptDistinct(image, digests)) {
                return image;
            }
        }
        return null;
    }

    private ArticleInlineImage buildValidated(String slotId,
                                              String section,
                                              String prompt,
                                              String sourceType,
                                              String sourceUrl,
                                              String sourcePageUrl,
                                              String author,
                                              String license,
                                              String attribution,
                                              String declaredMimeType) {
        if (!hasText(sourceUrl)) {
            return null;
        }
        ImageInfo info = inspect(sourceUrl, declaredMimeType);
        if (info == null || info.width < 480 || info.height < 270) {
            return null;
        }
        String persistedUrl = sourceUrl;
        if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")
                || sourceUrl.startsWith("data:image/")) {
            try {
                persistedUrl = generatedImageService.persistImageReference(sourceUrl);
            } catch (Exception ignored) {
                return null;
            }
            if (!hasText(persistedUrl)) {
                return null;
            }
            if (!persistedUrl.equals(sourceUrl)) {
                info = inspect(persistedUrl, null);
                if (info == null || info.width < 480 || info.height < 270) {
                    return null;
                }
            }
        }
        if ("open_image".equals(sourceType)
                && (!hasText(license) || !hasText(attribution) || !hasText(sourcePageUrl))) {
            return null;
        }
        return ArticleInlineImage.builder()
                .slotId(slotId)
                .sectionKey(hashText(section))
                .sectionTitle(section)
                .prompt(prompt)
                .alt(section + "配图")
                .sourceType(sourceType)
                .sourceUrl(persistedUrl)
                .sourcePageUrl(sourcePageUrl)
                .author(author)
                .license(license)
                .attribution(attribution)
                .mimeType(info.mimeType)
                .width(info.width)
                .height(info.height)
                .byteSize((long) info.bytes.length)
                .sha256(info.sha256)
                .status("READY")
                .build();
    }

    private boolean acceptDistinct(ArticleInlineImage image, Set<String> digests) {
        return image != null && image.ready() && !digests.contains(image.getSha256());
    }

    private ImageInfo inspect(String sourceUrl, String declaredMimeType) {
        byte[] bytes = readBytes(sourceUrl);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            return new ImageInfo(
                    bytes,
                    image.getWidth(),
                    image.getHeight(),
                    hasText(declaredMimeType) ? declaredMimeType : inferMime(bytes),
                    sha256(bytes));
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] readBytes(String sourceUrl) {
        try {
            if (sourceUrl.startsWith("/api/v1/image/file/")) {
                return generatedImageService.readFile(sourceUrl.substring("/api/v1/image/file/".length()));
            }
            if (sourceUrl.startsWith("data:image/") && sourceUrl.contains(",")) {
                return Base64.getDecoder().decode(sourceUrl.substring(sourceUrl.indexOf(',') + 1));
            }
            if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
                return null;
            }
            Request request = new Request.Builder().url(sourceUrl).get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null
                        || (body.contentLength() > MAX_IMAGE_BYTES && body.contentLength() >= 0)) {
                    return null;
                }
                byte[] bytes = body.bytes();
                return bytes.length <= MAX_IMAGE_BYTES ? bytes : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public byte[] readValidatedBytes(ArticleInlineImage image) {
        if (image == null || !image.ready()) {
            return null;
        }
        byte[] bytes = readBytes(image.getSourceUrl());
        if (bytes == null || !image.getSha256().equals(sha256(bytes))) {
            return null;
        }
        return bytes;
    }

    List<String> selectSections(String markdown) {
        List<String> sections = new ArrayList<>();
        Matcher matcher = H2.matcher(defaultText(markdown, ""));
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (!containsAny(title, "参考", "来源", "延伸阅读", "写在最后", "结语")) {
                sections.add(title);
            }
        }
        if (sections.size() <= 2) {
            return sections;
        }
        List<String> selected = new ArrayList<>();
        selected.add(sections.get(0));
        selected.add(sections.get(sections.size() / 2));
        if (sections.size() >= 4) {
            selected.add(sections.get(sections.size() - 1));
        }
        return selected;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String visualPrompt(String topic, String section) {
        return "微信公众号正文配图，主题：" + defaultText(topic, "行业观察")
                + "。支撑章节：" + section
                + "。编辑纪实风格，主体清晰，真实场景或信息可视化构图，横向 4:3，"
                + "不包含文字、品牌标识、水印、二维码，不使用空泛科技背景。";
    }

    private String inferMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) {
            return "image/jpeg";
        }
        return "image/unknown";
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest(bytes)) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (Exception e) {
            return hashText(new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    private static class ImageInfo {
        private final byte[] bytes;
        private final int width;
        private final int height;
        private final String mimeType;
        private final String sha256;

        private ImageInfo(byte[] bytes, int width, int height, String mimeType, String sha256) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.sha256 = sha256;
        }
    }
}
