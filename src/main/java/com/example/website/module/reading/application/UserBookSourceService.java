package com.example.website.module.reading.application;

import com.example.website.common.BusinessException;
import com.example.website.module.reading.api.dto.BookSourceEnabledRequest;
import com.example.website.module.reading.api.dto.BookSourceImportRequest;
import com.example.website.module.reading.api.dto.BookUserSourceView;
import com.example.website.module.reading.domain.UserBookSource;
import com.example.website.module.reading.infrastructure.persistence.UserBookSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBookSourceService {
    private static final int MAX_DEFINITION_LENGTH = 512 * 1024;
    private final UserBookSourceRepository repository;
    private final ObjectMapper objectMapper;

    public List<BookUserSourceView> list(Long userId) {
        List<BookUserSourceView> result = new ArrayList<>();
        for (UserBookSource source : repository.findByUserIdOrderByCreatedAtDesc(userId)) result.add(view(source));
        return result;
    }

    public BookUserSourceView importSource(Long userId, BookSourceImportRequest request) {
        String definition = request.getDefinition().trim();
        if (definition.length() > MAX_DEFINITION_LENGTH) throw new BusinessException("书源 JSON 不能超过 512KB");
        JsonNode json;
        try { json = objectMapper.readTree(definition); }
        catch (Exception ex) { throw new BusinessException("书源 JSON 格式无效"); }
        if (!json.isObject()) throw new BusinessException("书源必须是 JSON 对象");
        String name = text(json, "bookSourceName");
        String url = text(json, "bookSourceUrl");
        if (name.isEmpty() || url.isEmpty()) throw new BusinessException("书源缺少 bookSourceName 或 bookSourceUrl");
        validateUrl(url);
        String type = text(json, "bookSourceType");
        if (type.isEmpty()) type = "book";
        String key = "user-" + sha256(userId + ":" + url).substring(0, 24);
        UserBookSource source = repository.findByUserIdAndSourceKey(userId, key).orElseGet(UserBookSource::new);
        source.setUserId(userId);
        source.setSourceKey(key);
        source.setSourceName(name.length() > 120 ? name.substring(0, 120) : name);
        source.setSourceUrl(url);
        source.setSourceType(type.length() > 20 ? type.substring(0, 20) : type);
        source.setDefinitionJson(definition);
        boolean hasRules = json.has("ruleSearch") && json.has("ruleBookInfo") && json.has("ruleToc") && json.has("ruleContent");
        boolean hasScript = definition.contains("@js:") || definition.contains("java.") || definition.contains("Packages.");
        source.setEnabled(!hasScript && hasRules);
        source.setStatus(!hasRules ? "unsupported" : (hasScript ? "unsupported-js" : "ready"));
        source.setMessage(!hasRules ? "缺少完整书籍规则" : (hasScript ? "包含脚本规则，服务器不会执行" : "已导入，可在书源列表启用"));
        return view(repository.save(source));
    }

    public BookUserSourceView setEnabled(Long userId, Long id, BookSourceEnabledRequest request) {
        UserBookSource source = own(userId, id);
        if (request.getEnabled() && !"ready".equals(source.getStatus())) throw new BusinessException("该书源规则暂不支持执行");
        source.setEnabled(request.getEnabled());
        return view(repository.save(source));
    }

    public void delete(Long userId, Long id) { repository.delete(own(userId, id)); }

    private UserBookSource own(Long userId, Long id) {
        return repository.findById(id).filter(item -> item.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(404, "书源不存在"));
    }

    private BookUserSourceView view(UserBookSource source) {
        return new BookUserSourceView(source.getId(), source.getSourceKey(), source.getSourceName(), source.getSourceUrl(), source.getSourceType(), source.isEnabled(), source.getStatus(), source.getMessage());
    }

    private String text(JsonNode json, String field) { JsonNode node = json.get(field); return node == null || node.isNull() ? "" : node.asText("").trim(); }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new BusinessException("书源地址必须是有效的 HTTP/HTTPS URL"); }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
