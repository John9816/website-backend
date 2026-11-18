package com.example.website.editor;

import com.example.website.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学城编辑器 · 图片/音频上传。原版走外部图床 / R2，这里改为website后端本地存储，
 * 通过 /editor/api/media/{file} 同源对外提供，保持前端拿到的 url 契约不变（同源相对路径）。
 */
@Slf4j
@Service
public class EditorUploadService {

    private static final Path MEDIA_DIR = Paths.get("uploads", "editor-media");
    private static final long MAX_BYTES = 15L * 1024L * 1024L;
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:([^;,]+);base64,(.+)$", Pattern.DOTALL);

    private static final Map<String, String> EXT = new HashMap<>();
    static {
        EXT.put("image/png", "png");
        EXT.put("image/jpeg", "jpg");
        EXT.put("image/jpg", "jpg");
        EXT.put("image/gif", "gif");
        EXT.put("image/webp", "webp");
        EXT.put("image/svg+xml", "svg");
        EXT.put("audio/mpeg", "mp3");
        EXT.put("audio/mp3", "mp3");
        EXT.put("audio/wav", "wav");
        EXT.put("audio/ogg", "ogg");
        EXT.put("audio/mp4", "m4a");
        EXT.put("audio/x-m4a", "m4a");
    }

    /** 图片上传：返回 {url,name,source,deleteUrl}。 */
    public Map<String, Object> uploadImage(String dataUrl, String name) {
        return upload(dataUrl, name, true);
    }

    /** 音频上传：返回 {url,name,...}。 */
    public Map<String, Object> uploadAudio(String dataUrl, String name) {
        return upload(dataUrl, name, false);
    }

    private Map<String, Object> upload(String dataUrl, String name, boolean imageOnly) {
        Matcher m = DATA_URL.matcher(dataUrl == null ? "" : dataUrl.trim());
        if (!m.matches()) throw new BusinessException(400, "仅支持 dataUrl 上传");
        String mime = m.group(1).toLowerCase();
        String ext = EXT.get(mime);
        if (ext == null) throw new BusinessException(400, "不支持的媒体类型: " + mime);
        if (imageOnly && !mime.startsWith("image/")) throw new BusinessException(400, "仅支持图片 dataUrl");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(m.group(2));
        } catch (Exception e) {
            throw new BusinessException(400, "dataUrl base64 无效");
        }
        if (bytes.length == 0) throw new BusinessException(400, "空文件");
        if (bytes.length > MAX_BYTES) throw new BusinessException(413, "文件过大");

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.createDirectories(MEDIA_DIR);
            Files.write(MEDIA_DIR.resolve(filename), bytes);
        } catch (IOException e) {
            log.error("editor media write failed", e);
            throw new BusinessException(500, "存储失败");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", "/editor/api/media/" + filename);
        String safeName = name == null || name.isBlank() ? "file." + ext : name;
        out.put("name", safeName);
        out.put("source", "local");
        out.put("deleteUrl", "");
        return out;
    }

    /** 读取媒体文件用于响应。 */
    public ResponseEntity<byte[]> serve(String filename) {
        if (!filename.matches("^[A-Za-z0-9._-]+$")) throw new BusinessException(400, "无效文件名");
        Path path = MEDIA_DIR.resolve(filename).normalize();
        if (!path.startsWith(MEDIA_DIR) || !Files.exists(path)) throw new BusinessException(404, "文件不存在");
        try {
            byte[] data = Files.readAllBytes(path);
            MediaType mt = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().contentType(mt).body(data);
        } catch (IOException e) {
            throw new BusinessException(500, "读取失败");
        }
    }
}
