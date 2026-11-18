package com.example.website.module.editor.application;

import com.example.website.common.BusinessException;
import com.example.website.module.editor.domain.EditorPresence;
import com.example.website.module.editor.domain.EditorShare;
import com.example.website.module.editor.infrastructure.persistence.EditorPresenceRepository;
import com.example.website.module.editor.infrastructure.persistence.EditorShareRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学城文档业务服务。完整复刻原 Node 版 server/xuecheng 的行为：
 * 文档 CRUD / 分享 / 回收站 / 通知 / 评论 / 提及 / 权限审批 / 在线心跳 / 统计 / 模板 / 上传。
 * MCP 不迁移（按需求）。
 */
@Service
@RequiredArgsConstructor
public class EditorService {

    private final EditorStore store;
    private final EditorShareRepository shareRepo;
    private final EditorPresenceRepository presenceRepo;
    private final ObjectMapper mapper;

    @Value("${app.editor.file-dir:./data/editor/files}")
    private String fileDir;

    @Value("${app.editor.public-base:/api/public/editor}")
    private String publicBase;

    private static final long PRESENCE_TTL_MS = 60_000L;
    private static final int MAX_DOCS = 2000;

    // ------------------------------------------------------------------ 工具
    private String nowISO() { return Instant.now().toString(); }
    private String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private boolean bool(Object o) { return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(str(o)); }

    /** ProseMirror JSON 提取纯文本摘要 */
    private String excerptOf(Object content, int max) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(content, sb, max);
        return sb.toString().replaceAll("\\s+", " ").trim().substring(0, Math.min(max, sb.toString().replaceAll("\\s+", " ").trim().length()));
    }

    private void walk(Object nd, StringBuilder sb, int max) {
        if (nd == null || sb.length() >= max) return;
        if (nd instanceof String) { sb.append(nd); return; }
        if (nd instanceof List) { for (Object x : (List<?>) nd) walk(x, sb, max); return; }
        if (nd instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) nd;
            Object type = m.get("type");
            if ("text".equals(type)) sb.append(str(m.get("text")) == null ? "" : m.get("text"));
            else if (m.get("content") != null) walk(m.get("content"), sb, max);
        }
    }

    // ================================================================== 文档
    /** 列表：返回摘要卡片 */
    public List<Object> listDocs(Long userId) {
        List<Object> docs = store.readColl(userId, EditorStore.Coll.DOCS);
        List<Object> out = new ArrayList<>();
        for (Object o : docs) {
            Map<String, Object> d = asMap(o);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.get("id"));
            item.put("title", d.get("title"));
            item.put("parentId", d.get("parentId") != null ? d.get("parentId") : null);
            item.put("updatedAt", d.get("updatedAt"));
            item.put("createdAt", d.get("createdAt"));
            item.put("favorite", bool(d.get("favorite")));
            item.put("lastVisitedAt", d.get("lastVisitedAt") != null ? d.get("lastVisitedAt") : null);
            Map<String, Object> share = asMap(d.get("share"));
            item.put("shared", bool(share.get("enabled")));
            item.put("excerpt", excerptOf(d.get("content"), 120));
            out.add(item);
        }
        return out;
    }

    /** 新建 */
    public Map<String, Object> createDoc(Long userId, Map<String, Object> body) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.DOCS);
        String parentId = body.get("parentId") == null ? null : str(body.get("parentId"));
        if (parentId != null && list.stream().noneMatch(d -> parentId.equals(str(asMap(d).get("id"))))) {
            throw new BusinessException(400, "父文档不存在");
        }
        if (list.size() >= MAX_DOCS) throw new BusinessException(400, "文档数量已达上限");
        String title = body.get("title") == null ? "未命名文档" : str(body.get("title"));
        if (title.length() > 120) title = title.substring(0, 120);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", newId());
        doc.put("title", title);
        doc.put("content", body.get("content"));
        doc.put("parentId", parentId);
        doc.put("favorite", false);
        doc.put("lastVisitedAt", null);
        doc.put("share", null);
        doc.put("createdAt", nowISO());
        doc.put("updatedAt", nowISO());
        list.add(doc);
        store.writeColl(userId, EditorStore.Coll.DOCS, list);
        return doc;
    }

    /** 单篇读取 */
    public Map<String, Object> getDoc(Long userId, String id) {
        return findDoc(userId, id);
    }

    private Map<String, Object> findDoc(Long userId, String id) {
        for (Object o : store.readColl(userId, EditorStore.Coll.DOCS)) {
            Map<String, Object> d = asMap(o);
            if (id.equals(str(d.get("id")))) return d;
        }
        throw new BusinessException(404, "文档不存在");
    }

    /** 更新（PUT/PATCH）：标题、正文、收藏、最近访问、移动 */
    public Map<String, Object> updateDoc(Long userId, String id, Map<String, Object> body) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.DOCS);
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(str(asMap(list.get(i)).get("id")))) { idx = i; break; }
        }
        if (idx < 0) throw new BusinessException(404, "文档不存在");
        Map<String, Object> doc = asMap(list.get(idx));
        boolean edited = false;
        if (body.get("title") instanceof String) {
            String t = (String) body.get("title");
            doc.put("title", t.length() > 120 ? t.substring(0, 120) : t);
            edited = true;
        }
        if (body.containsKey("content")) { doc.put("content", body.get("content")); edited = true; }
        if (body.get("favorite") instanceof Boolean) doc.put("favorite", body.get("favorite"));
        if (body.containsKey("lastVisitedAt")) doc.put("lastVisitedAt", body.get("lastVisitedAt"));
        if (body.containsKey("parentId")) {
            String pid = body.get("parentId") == null ? null : str(body.get("parentId"));
            if (pid != null) {
                if (pid.equals(id)) throw new BusinessException(400, "不能移动到自身之下");
                // 防环：目标不能是自身后代
                String cur = pid;
                Set<String> seen = new HashSet<>();
                while (cur != null) {
                    if (id.equals(cur)) throw new BusinessException(400, "不能移动到自身或其子文档下");
                    if (seen.contains(cur)) break;
                    seen.add(cur);
                    String finalCur = cur;
                    Optional<Object> parent = list.stream().filter(d -> finalCur.equals(str(asMap(d).get("id")))).findFirst();
                    cur = parent.map(d -> str(asMap(d).get("parentId"))).orElse(null);
                }
            }
            doc.put("parentId", pid);
        }
        if (edited) doc.put("updatedAt", nowISO());
        list.set(idx, doc);
        store.writeColl(userId, EditorStore.Coll.DOCS, list);
        return doc;
    }

    /** 删除：级联删除自身及所有子孙，写入回收站，清理分享映射 */
    public Map<String, Object> deleteDoc(Long userId, String id, String fileDirForShare) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.DOCS);
        Set<String> toDelete = new LinkedHashSet<>();
        toDelete.add(id);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Object o : list) {
                Map<String, Object> d = asMap(o);
                String pid = str(d.get("parentId"));
                String did = str(d.get("id"));
                if (pid != null && toDelete.contains(pid) && !toDelete.contains(did)) {
                    toDelete.add(did); changed = true;
                }
            }
        }
        List<Object> removed = list.stream().filter(o -> toDelete.contains(str(asMap(o).get("id")))).collect(Collectors.toList());
        List<Object> remaining = list.stream().filter(o -> !toDelete.contains(str(asMap(o).get("id")))).collect(Collectors.toList());

        List<String> tokens = new ArrayList<>();
        for (Object o : removed) {
            String t = str(asMap(asMap(o).get("share")).get("token"));
            if (t != null) tokens.add(t);
        }
        // 回收站快照
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("id", newId());
        batch.put("at", nowISO());
        batch.put("reason", "delete");
        batch.put("docs", removed);
        batch.put("shareTokens", tokens);
        pushTrash(userId, batch);

        store.writeColl(userId, EditorStore.Coll.DOCS, remaining);
        for (String t : tokens) shareRepo.deleteByToken(t);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("count", removed.size());
        r.put("trashId", batch.get("id"));
        return r;
    }

    // ================================================================== 分享
    public Map<String, Object> getShareState(Long userId, String id) {
        Map<String, Object> doc = findDoc(userId, id);
        Map<String, Object> cur = asMap(doc.get("share"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", bool(cur.get("enabled")));
        r.put("token", cur.get("token") != null ? cur.get("token") : null);
        r.put("views", cur.get("views") != null ? cur.get("views") : 0);
        r.put("createdAt", cur.get("createdAt"));
        r.put("path", cur.get("token") != null ? "#/s/" + cur.get("token") : null);
        return r;
    }

    public Map<String, Object> enableShare(Long userId, String id) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.DOCS);
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (id.equals(str(asMap(list.get(i)).get("id")))) { idx = i; break; }
        if (idx < 0) throw new BusinessException(404, "文档不存在");
        Map<String, Object> doc = asMap(list.get(idx));
        Map<String, Object> cur = asMap(doc.get("share"));
        String token = cur.get("token") != null ? str(cur.get("token")) : Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(12));
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("token", token);
        share.put("enabled", true);
        share.put("views", cur.get("views") != null ? cur.get("views") : 0);
        share.put("createdAt", cur.get("createdAt") != null ? cur.get("createdAt") : nowISO());
        share.put("updatedAt", nowISO());
        doc.put("share", share);
        list.set(idx, doc);
        store.writeColl(userId, EditorStore.Coll.DOCS, list);
        // 全局 token 映射
        Optional<EditorShare> exist = shareRepo.findByToken(token);
        EditorShare es = exist.orElseGet(EditorShare::new);
        es.setToken(token); es.setUserId(userId); es.setDocId(id);
        if (es.getCreatedAt() == null) es.setCreatedAt(java.time.LocalDateTime.now());
        shareRepo.save(es);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", true); r.put("token", token);
        r.put("views", share.get("views")); r.put("createdAt", share.get("createdAt"));
        r.put("path", "#/s/" + token);
        return r;
    }

    public Map<String, Object> disableShare(Long userId, String id) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.DOCS);
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> doc = asMap(list.get(i));
            if (id.equals(str(doc.get("id")))) {
                Map<String, Object> cur = asMap(doc.get("share"));
                if (!cur.isEmpty()) {
                    cur.put("enabled", false); cur.put("updatedAt", nowISO());
                    doc.put("share", cur);
                    list.set(i, doc);
                    store.writeColl(userId, EditorStore.Coll.DOCS, list);
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("enabled", false); r.put("token", cur.get("token"));
                return r;
            }
        }
        throw new BusinessException(404, "文档不存在");
    }

    /** 公开分享读取（无鉴权，凭 token） */
    public Map<String, Object> readShared(String token) {
        Optional<EditorShare> esOpt = shareRepo.findByToken(token);
        if (!esOpt.isPresent()) throw new BusinessException(404, "分享链接不存在或已被关闭");
        Long owner = esOpt.get().getUserId();
        String docId = esOpt.get().getDocId();
        List<Object> list = store.readColl(owner, EditorStore.Coll.DOCS);
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> doc = asMap(list.get(i));
            if (docId != null && docId.equals(str(doc.get("id")))) {
                Map<String, Object> share = asMap(doc.get("share"));
                if (!bool(share.get("enabled")) || !token.equals(str(share.get("token")))) break;
                share.put("views", ((Number) share.getOrDefault("views", 0)).longValue() + 1);
                doc.put("share", share);
                list.set(i, doc);
                store.writeColl(owner, EditorStore.Coll.DOCS, list);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("title", doc.get("title")); r.put("content", doc.get("content"));
                r.put("createdAt", doc.get("createdAt")); r.put("updatedAt", doc.get("updatedAt"));
                r.put("views", share.get("views"));
                return r;
            }
        }
        throw new BusinessException(404, "分享链接不存在或已被关闭");
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(System.nanoTime()).nextBytes(b);
        return b;
    }

    // ================================================================== 回收站
    private void pushTrash(Long userId, Map<String, Object> batch) {
        List<Object> batches = store.readColl(userId, EditorStore.Coll.TRASH);
        batches.add(batch);
        while (batches.size() > 50) batches.remove(0);
        store.writeColl(userId, EditorStore.Coll.TRASH, batches);
    }

    public List<Object> listTrash(Long userId) {
        List<Object> batches = store.readColl(userId, EditorStore.Coll.TRASH);
        batches.sort((a, b) -> str(asMap(b).get("at")).compareTo(str(asMap(a).get("at"))));
        return batches;
    }

    public Map<String, Object> restoreTrash(Long userId, String batchId) {
        List<Object> batches = store.readColl(userId, EditorStore.Coll.TRASH);
        int idx = -1;
        for (int i = 0; i < batches.size(); i++) if (batchId.equals(str(asMap(batches.get(i)).get("id")))) { idx = i; break; }
        if (idx < 0) throw new BusinessException(404, "回收站记录不存在");
        Map<String, Object> batch = asMap(batches.get(idx));
        List<Object> docs = store.readColl(userId, EditorStore.Coll.DOCS);
        Set<String> existing = docs.stream().map(d -> str(asMap(d).get("id"))).collect(Collectors.toSet());
        Set<String> batchDocIds = ((List<?>) batch.getOrDefault("docs", new ArrayList<>())).stream()
                .map(d -> str(asMap(d).get("id"))).collect(Collectors.toSet());
        int restored = 0;
        for (Object snapO : (List<?>) batch.getOrDefault("docs", new ArrayList<>())) {
            Map<String, Object> snap = new LinkedHashMap<>(asMap(snapO));
            String sid = str(snap.get("id"));
            if (existing.contains(sid)) continue;
            String pid = str(snap.get("parentId"));
            boolean parentOk = pid == null || existing.contains(pid) || batchDocIds.contains(pid);
            snap.put("parentId", parentOk ? pid : null);
            docs.add(snap);
            existing.add(sid);
            restored++;
        }
        store.writeColl(userId, EditorStore.Coll.DOCS, docs);
        // 恢复分享 token 映射
        for (Object t : (List<?>) batch.getOrDefault("shareTokens", new ArrayList<>())) {
            String token = str(t);
            if (token == null || shareRepo.findByToken(token).isPresent()) continue;
            EditorShare es = new EditorShare();
            es.setToken(token); es.setUserId(userId);
            es.setCreatedAt(java.time.LocalDateTime.now());
            shareRepo.save(es);
        }
        batches.remove(idx);
        store.writeColl(userId, EditorStore.Coll.TRASH, batches);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restored", restored);
        return r;
    }

    public Map<String, Object> purgeTrash(Long userId, String batchId) {
        List<Object> batches = store.readColl(userId, EditorStore.Coll.TRASH);
        boolean removed = batches.removeIf(b -> batchId.equals(str(asMap(b).get("id"))));
        if (!removed) throw new BusinessException(404, "回收站记录不存在");
        store.writeColl(userId, EditorStore.Coll.TRASH, batches);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("id", batchId); return r;
    }

    // ================================================================== 通知
    private void addNotification(Long userId, Map<String, Object> note) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.NOTIFICATIONS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", newId());
        item.put("type", note.get("type") != null ? note.get("type") : "system");
        item.put("docId", note.get("docId"));
        item.put("docTitle", note.get("docTitle") != null ? note.get("docTitle") : "");
        item.put("actor", note.get("actor") != null ? note.get("actor") : "");
        String text = str(note.get("text"));
        item.put("text", text == null ? "" : (text.length() > 300 ? text.substring(0, 300) : text));
        item.put("read", false);
        item.put("createdAt", nowISO());
        list.add(item);
        while (list.size() > 500) list.remove(0);
        store.writeColl(userId, EditorStore.Coll.NOTIFICATIONS, list);
    }

    public List<Object> listNotifications(Long userId, boolean unreadOnly) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.NOTIFICATIONS);
        if (unreadOnly) list = list.stream().filter(n -> !bool(asMap(n).get("read"))).collect(Collectors.toList());
        list.sort((a, b) -> str(asMap(b).get("createdAt")).compareTo(str(asMap(a).get("createdAt"))));
        return list;
    }

    public Map<String, Object> readAllNotifications(Long userId, List<String> ids) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.NOTIFICATIONS);
        Set<String> set = (ids != null && !ids.isEmpty()) ? new HashSet<>(ids) : null;
        boolean changed = false;
        for (Object o : list) {
            Map<String, Object> n = asMap(o);
            if ((set == null || set.contains(str(n.get("id")))) && !bool(n.get("read"))) {
                n.put("read", true); changed = true;
            }
        }
        if (changed) store.writeColl(userId, EditorStore.Coll.NOTIFICATIONS, list);
        long read = list.stream().filter(n -> bool(asMap(n).get("read"))).count();
        Map<String, Object> r = new LinkedHashMap<>(); r.put("read", read); return r;
    }

    // ================================================================== 评论
    public List<Object> listComments(Long userId, String docId) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.COMMENTS);
        if (docId != null) list = list.stream().filter(c -> docId.equals(str(asMap(c).get("docId")))).collect(Collectors.toList());
        return list;
    }

    public Map<String, Object> addComment(Long userId, String docId, String text, String username) {
        if (docId == null) throw new BusinessException(400, "缺少文档 id");
        if (text == null || text.trim().isEmpty()) throw new BusinessException(400, "评论内容不能为空");
        List<Object> list = store.readColl(userId, EditorStore.Coll.COMMENTS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", newId()); item.put("docId", docId);
        item.put("author", username != null ? username : ""); item.put("authorId", userId);
        String t = text.length() > 2000 ? text.substring(0, 2000) : text;
        item.put("text", t); item.put("createdAt", nowISO()); item.put("replies", new ArrayList<>());
        list.add(item);
        store.writeColl(userId, EditorStore.Coll.COMMENTS, list);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "comment"); note.put("docId", docId);
        note.put("actor", username); note.put("text", "你发表了一条评论");
        addNotification(userId, note);
        return item;
    }

    public Map<String, Object> replyComment(Long userId, String commentId, String text, String username) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.COMMENTS);
        for (Object o : list) {
            Map<String, Object> c = asMap(o);
            if (commentId.equals(str(c.get("id")))) {
                List<Object> replies = new ArrayList<>(c.get("replies") instanceof List ? (List<Object>) c.get("replies") : new ArrayList<>());
                Map<String, Object> rp = new LinkedHashMap<>();
                rp.put("id", newId()); rp.put("author", username != null ? username : ""); rp.put("authorId", userId);
                String t = text == null ? "" : (text.length() > 2000 ? text.substring(0, 2000) : text);
                rp.put("text", t); rp.put("createdAt", nowISO());
                replies.add(rp); c.put("replies", replies);
                store.writeColl(userId, EditorStore.Coll.COMMENTS, list);
                return c;
            }
        }
        throw new BusinessException(404, "评论不存在");
    }

    public Map<String, Object> deleteComment(Long userId, String commentId) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.COMMENTS);
        boolean removed = list.removeIf(c -> commentId.equals(str(asMap(c).get("id"))));
        if (!removed) throw new BusinessException(404, "评论不存在或无权限");
        store.writeColl(userId, EditorStore.Coll.COMMENTS, list);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("id", commentId); return r;
    }

    // ================================================================== 提及
    public List<Object> listMentions(Long userId, boolean unreadOnly) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.MENTIONS);
        if (unreadOnly) list = list.stream().filter(m -> !bool(asMap(m).get("read"))).collect(Collectors.toList());
        list.sort((a, b) -> str(asMap(b).get("createdAt")).compareTo(str(asMap(a).get("createdAt"))));
        return list;
    }

    public Map<String, Object> addMention(Long userId, Map<String, Object> body, String username) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.MENTIONS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", newId()); item.put("docId", body.get("docId"));
        item.put("docTitle", body.get("docTitle") != null ? body.get("docTitle") : "");
        item.put("by", username != null ? username : ""); item.put("byId", userId);
        String text = str(body.get("text"));
        item.put("text", text == null ? "" : (text.length() > 200 ? text.substring(0, 200) : text));
        item.put("read", false); item.put("createdAt", nowISO());
        list.add(item);
        store.writeColl(userId, EditorStore.Coll.MENTIONS, list);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "mention"); note.put("docId", body.get("docId"));
        note.put("docTitle", body.get("docTitle")); note.put("actor", username);
        note.put("text", "有人在文档中提到了你");
        addNotification(userId, note);
        return item;
    }

    public Map<String, Object> readAllMentions(Long userId, List<String> ids) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.MENTIONS);
        Set<String> set = (ids != null && !ids.isEmpty()) ? new HashSet<>(ids) : null;
        boolean changed = false;
        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            if ((set == null || set.contains(str(m.get("id")))) && !bool(m.get("read"))) { m.put("read", true); changed = true; }
        }
        if (changed) store.writeColl(userId, EditorStore.Coll.MENTIONS, list);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("ok", true); r.put("total", list.size()); return r;
    }

    // ================================================================== 权限申请 / 审批
    public List<Object> listAccessRequests(Long userId, boolean inbox) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        if (inbox) list = list.stream().filter(r -> "pending".equals(str(asMap(r).get("status")))).collect(Collectors.toList());
        list.sort((a, b) -> str(asMap(b).get("createdAt")).compareTo(str(asMap(a).get("createdAt"))));
        return list;
    }

    public Map<String, Object> addAccessRequest(Long userId, Map<String, Object> body, String username) {
        String docId = str(body.get("docId"));
        if (docId == null) throw new BusinessException(400, "缺少文档 id");
        String docTitle = str(body.get("docTitle"));
        if (docTitle == null) {
            for (Object o : store.readColl(userId, EditorStore.Coll.DOCS)) {
                Map<String, Object> d = asMap(o);
                if (docId.equals(str(d.get("id")))) { docTitle = str(d.get("title")); break; }
            }
        }
        List<Object> list = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", newId()); item.put("docId", docId);
        item.put("docTitle", docTitle != null ? docTitle : "");
        item.put("applicant", username != null ? username : ""); item.put("applicantId", userId);
        item.put("role", body.get("role") != null ? body.get("role") : "viewer");
        String msg = str(body.get("message"));
        item.put("message", msg == null ? "" : (msg.length() > 300 ? msg.substring(0, 300) : msg));
        item.put("status", "pending"); item.put("handledBy", null); item.put("handledAt", null);
        item.put("createdAt", nowISO());
        list.add(item);
        store.writeColl(userId, EditorStore.Coll.ACCESS_REQUESTS, list);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "access_request"); note.put("docId", docId);
        note.put("docTitle", docTitle); note.put("actor", username);
        note.put("text", "收到一条文档访问申请");
        addNotification(userId, note);
        return item;
    }

    public Map<String, Object> decideAccessRequest(Long userId, String id, boolean approve, String username) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        for (Object o : list) {
            Map<String, Object> r = asMap(o);
            if (id.equals(str(r.get("id"))) && "pending".equals(str(r.get("status")))) {
                r.put("status", approve ? "approved" : "rejected");
                r.put("handledBy", username); r.put("handledAt", nowISO());
                store.writeColl(userId, EditorStore.Coll.ACCESS_REQUESTS, list);
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", "access_decision"); note.put("docId", r.get("docId"));
                note.put("docTitle", r.get("docTitle")); note.put("actor", username);
                note.put("text", approve ? "你的访问申请已通过" : "你的访问申请被拒绝");
                addNotification(userId, note);
                return r;
            }
        }
        throw new BusinessException(404, "申请不存在或已处理");
    }

    // ================================================================== 在线 / 心跳
    public Map<String, Object> presence() {
        long now = System.currentTimeMillis();
        List<EditorPresence> all = presenceRepo.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        List<EditorPresence> expired = new ArrayList<>();
        for (EditorPresence p : all) {
            if (p.getAt() != null && now - p.getAt() < PRESENCE_TTL_MS) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("name", p.getName()); v.put("at", p.getAt()); v.put("docId", p.getDocId());
                out.put(String.valueOf(p.getUserId()), v);
            } else {
                expired.add(p);
            }
        }
        if (!expired.isEmpty()) presenceRepo.deleteAll(expired);
        return out;
    }

    public Map<String, Object> heartbeat(Long userId, String username, String docId) {
        EditorPresence p = presenceRepo.findById(userId).orElseGet(EditorPresence::new);
        p.setUserId(userId); p.setName(username != null ? username : String.valueOf(userId));
        p.setDocId(docId); p.setAt(System.currentTimeMillis());
        presenceRepo.save(p);
        return presence();
    }

    // ================================================================== 统计
    public Map<String, Object> stats(Long userId) {
        List<Object> docs = store.readColl(userId, EditorStore.Coll.DOCS);
        long chars = 0;
        for (Object d : docs) {
            Object c = asMap(d).get("content");
            chars += c == null ? 0 : String.valueOf(c).length();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docs", docs.size());
        r.put("favorites", docs.stream().filter(d -> bool(asMap(d).get("favorite"))).count());
        r.put("shared", docs.stream().filter(d -> bool(asMap(asMap(d).get("share")).get("enabled"))).count());
        r.put("comments", store.readColl(userId, EditorStore.Coll.COMMENTS).size());
        r.put("notifications", store.readColl(userId, EditorStore.Coll.NOTIFICATIONS).size());
        r.put("trashBatches", store.readColl(userId, EditorStore.Coll.TRASH).size());
        r.put("totalChars", chars);
        return r;
    }

    // ================================================================== 模板
    public List<Object> listTemplates(Long userId) {
        return store.readColl(userId, EditorStore.Coll.TEMPLATES);
    }

    public Map<String, Object> getTemplate(Long userId, String id) {
        for (Object o : store.readColl(userId, EditorStore.Coll.TEMPLATES)) {
            Map<String, Object> t = asMap(o);
            if (id.equals(str(t.get("id")))) return t;
        }
        throw new BusinessException(404, "模板不存在");
    }

    public Map<String, Object> createTemplate(Long userId, Map<String, Object> body) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.TEMPLATES);
        Map<String, Object> item = new LinkedHashMap<>(body);
        item.put("id", newId());
        if (!item.containsKey("createdAt")) item.put("createdAt", nowISO());
        item.put("updatedAt", nowISO());
        list.add(item);
        store.writeColl(userId, EditorStore.Coll.TEMPLATES, list);
        return item;
    }

    public Map<String, Object> updateTemplate(Long userId, String id, Map<String, Object> body) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.TEMPLATES);
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> t = asMap(list.get(i));
            if (id.equals(str(t.get("id")))) {
                Map<String, Object> merged = new LinkedHashMap<>(t);
                merged.putAll(body); merged.put("id", id); merged.put("updatedAt", nowISO());
                list.set(i, merged);
                store.writeColl(userId, EditorStore.Coll.TEMPLATES, list);
                return merged;
            }
        }
        throw new BusinessException(404, "模板不存在");
    }

    public Map<String, Object> removeTemplate(Long userId, String id) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.TEMPLATES);
        boolean removed = list.removeIf(t -> id.equals(str(asMap(t).get("id"))));
        if (!removed) throw new BusinessException(404, "模板不存在");
        store.writeColl(userId, EditorStore.Coll.TEMPLATES, list);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("id", id); return r;
    }

    // ================================================================== 上传
    public Map<String, Object> upload(Long userId, String dataUrl, boolean audio) {
        String pattern = audio
                ? "^data:audio/(mpeg|mp3|wav|ogg|mp4|x-wav);base64,(.+)$"
                : "^data:image/(png|jpeg|jpg|gif|webp|svg\\+xml);base64,(.+)$";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dataUrl == null ? "" : dataUrl);
        if (!m.matches()) throw new BusinessException(400, audio ? "仅支持 audio dataUrl" : "仅支持图片 dataUrl");
        String mime = m.group(1).toLowerCase();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(m.group(2)); }
        catch (Exception e) { throw new BusinessException(400, "文件内容无效"); }
        long limit = audio ? 12L * 1024 * 1024 : 5L * 1024 * 1024;
        if (bytes.length > limit) throw new BusinessException(413, audio ? "音频不能超过 12MB" : "图片不能超过 5MB");
        String ext;
        if (audio) {
            Map<String, String> map = new HashMap<>();
            map.put("mpeg", "mp3"); map.put("mp3", "mp3"); map.put("wav", "wav");
            map.put("x-wav", "wav"); map.put("ogg", "ogg"); map.put("mp4", "m4a");
            ext = map.getOrDefault(mime, "mp3");
        } else {
            Map<String, String> map = new HashMap<>();
            map.put("jpeg", "jpg"); map.put("svg+xml", "svg");
            ext = map.getOrDefault(mime, mime);
        }
        String name = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + ext;
        try {
            Path dir = Paths.get(fileDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (Exception e) {
            throw new BusinessException(500, "文件保存失败");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("url", publicBase + "/files/" + name);
        r.put("name", name);
        return r;
    }

    /** 公开文件读取 */
    public byte[] readFile(String name) {
        if (!name.matches("[A-Za-z0-9]{36}\\.(png|jpg|gif|webp|svg|mp3|wav|ogg|m4a)")) {
            throw new BusinessException(404, "文件不存在");
        }
        try {
            Path p = Paths.get(fileDir).resolve(name).normalize();
            if (!Files.exists(p)) throw new BusinessException(404, "文件不存在");
            return Files.readAllBytes(p);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(404, "文件不存在");
        }
    }

    public String fileContentType(String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        Map<String, String> map = new HashMap<>();
        map.put("png", "image/png"); map.put("jpg", "image/jpeg"); map.put("gif", "image/gif");
        map.put("webp", "image/webp"); map.put("svg", "image/svg+xml");
        map.put("mp3", "audio/mpeg"); map.put("wav", "audio/wav"); map.put("ogg", "audio/ogg"); map.put("m4a", "audio/mp4");
        return map.getOrDefault(ext, "application/octet-stream");
    }
}
