package com.example.website.module.editor.application;

import com.example.website.module.editor.domain.EditorUserData;
import com.example.website.module.editor.infrastructure.persistence.EditorUserDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return parseRaw(rawOf(d, coll));
    }

    /** 从一行数据里取某集合的原始 JSON 字符串。 */
    private String rawOf(EditorUserData d, Coll coll) {
        if (d == null) return null;
        switch (coll) {
            case DOCS: return d.getDocs();
            case TEMPLATES: return d.getTemplates();
            case TRASH: return d.getTrash();
            case NOTIFICATIONS: return d.getNotifications();
            case COMMENTS: return d.getComments();
            case MENTIONS: return d.getMentions();
            case ACCESS_REQUESTS: return d.getAccessRequests();
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseRaw(String raw) {
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

    /** 读指定数据行的集合（跨用户场景用，不经 row(userId) 自动建行）。 */
    public List<Object> readCollOf(EditorUserData d, Coll coll) {
        return parseRaw(rawOf(d, coll));
    }

    /** 列出全部用户数据行（用于按 docId 全局定位文档所有者）。 */
    public List<EditorUserData> allRows() {
        return repository.findAll();
    }

    /**
     * 全局按文档 id 定位所属用户。docId 为随机全局唯一值，用户量级很小（个位数），
     * 遍历所有行寻找包含该 id 的文档，返回 ownerId；找不到返回 null。
     */
    @SuppressWarnings("unchecked")
    public Long findOwnerOfDoc(String docId) {
        if (docId == null || docId.isEmpty()) return null;
        for (EditorUserData d : allRows()) {
            for (Object o : parseRaw(rawOf(d, Coll.DOCS))) {
                if (o instanceof Map && docId.equals(String.valueOf(((Map<String, Object>) o).get("id")))) {
                    return d.getUserId();
                }
            }
        }
        return null;
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
