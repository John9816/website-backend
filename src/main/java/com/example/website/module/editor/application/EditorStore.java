package com.example.website.module.editor.application;

import com.example.website.module.editor.domain.EditorUserData;
import com.example.website.module.editor.infrastructure.persistence.EditorUserDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户文档集合访问层。等价于原 Node 的 userStore：
 * 以 JSON 数组字符串读写各集合，文件不存在（字段为 null）时返回空数组。
 * 所有修改通过 writeColl 落库。
 */
@Component
@RequiredArgsConstructor
public class EditorStore {

    private final EditorUserDataRepository repository;
    private final ObjectMapper mapper;

    public enum Coll {
        DOCS, TEMPLATES, TRASH, NOTIFICATIONS, COMMENTS, MENTIONS, ACCESS_REQUESTS
    }

    /** 读取（或首次创建）用户数据行。 */
    public EditorUserData row(Long userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            EditorUserData d = new EditorUserData();
            d.setUserId(userId);
            return d;
        });
    }

    @SuppressWarnings("unchecked")
    public List<Object> readColl(Long userId, Coll coll) {
        EditorUserData d = row(userId);
        String raw;
        switch (coll) {
            case DOCS: raw = d.getDocs(); break;
            case TEMPLATES: raw = d.getTemplates(); break;
            case TRASH: raw = d.getTrash(); break;
            case NOTIFICATIONS: raw = d.getNotifications(); break;
            case COMMENTS: raw = d.getComments(); break;
            case MENTIONS: raw = d.getMentions(); break;
            case ACCESS_REQUESTS: raw = d.getAccessRequests(); break;
            default: raw = null;
        }
        if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim())) {
            return new ArrayList<>();
        }
        try {
            Object value = mapper.readValue(raw, Object.class);
            if (value instanceof List) return (List<Object>) value;
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void writeColl(Long userId, Coll coll, List<?> value) {
        EditorUserData d = row(userId);
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("文档数据序列化失败", e);
        }
        switch (coll) {
            case DOCS: d.setDocs(json); break;
            case TEMPLATES: d.setTemplates(json); break;
            case TRASH: d.setTrash(json); break;
            case NOTIFICATIONS: d.setNotifications(json); break;
            case COMMENTS: d.setComments(json); break;
            case MENTIONS: d.setMentions(json); break;
            case ACCESS_REQUESTS: d.setAccessRequests(json); break;
            default: return;
        }
        repository.save(d);
    }
}
