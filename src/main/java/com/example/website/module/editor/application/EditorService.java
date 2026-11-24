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
import org.springframework.jdbc.core.JdbcTemplate;

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
    private final JdbcTemplate jdbcTemplate;

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

    /**
     * 我当前持有的有效授权记录（kind=grant 且 status=active）。
     * 授权记录写在被授权者自己的 access_requests 集合里，随用户隔离。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> myActiveGrants(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS)) {
            Map<String, Object> r = asMap(o);
            if ("grant".equals(str(r.get("kind"))) && "active".equals(str(r.get("status")))) out.add(r);
        }
        return out;
    }

    /** 按授权记录，从所有者行里取出原文档；文档被删或授权失效返回 null。 */
    private Map<String, Object> grantedDoc(Map<String, Object> grant) {
        Long owner = parseLong(grant.get("ownerId"));
        String docId = str(grant.get("docId"));
        if (owner == null || docId == null) return null;
        for (Object o : store.readColl(owner, EditorStore.Coll.DOCS)) {
            Map<String, Object> d = asMap(o);
            if (docId.equals(str(d.get("id")))) return d;
        }
        return null;
    }

    private static Long parseLong(Object v) {
        if (v == null) return null;
        try { return Long.valueOf(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    /** 列表：返回摘要卡片（自己的文档 + 别人授权给我的只读文档） */
    public List<Object> listDocs(Long userId) {
        List<Object> docs = store.readColl(userId, EditorStore.Coll.DOCS);
        List<Object> out = new ArrayList<>();
        for (Object o : docs) {
            Map<String, Object> d = asMap(o);
            Map<String, Object> item = docCard(d);
            item.put("permission", "owner");
            out.add(item);
        }
        // 被授权给我的文档
        for (Map<String, Object> grant : myActiveGrants(userId)) {
            Map<String, Object> d = grantedDoc(grant);
            if (d == null) continue;
            Map<String, Object> item = docCard(d);
            String role = str(grant.get("role"));
            item.put("permission", role == null ? "viewer" : role);
            item.put("readOnly", !"editor".equals(role));
            item.put("granted", true);
            item.put("ownerName", str(grant.get("ownerName")));
            out.add(item);
        }
        return out;
    }

    /** 单篇文档 -> 列表摘要卡片 */
    private Map<String, Object> docCard(Map<String, Object> d) {
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
        return item;
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

    /** 单篇读取：自己的文档，或别人授权给我的文档（只读） */
    public Map<String, Object> getDoc(Long userId, String id) {
        for (Object o : store.readColl(userId, EditorStore.Coll.DOCS)) {
            Map<String, Object> d = asMap(o);
            if (id.equals(str(d.get("id")))) {
                Map<String, Object> r = new LinkedHashMap<>(d);
                r.put("permission", "owner");
                return r;
            }
        }
        for (Map<String, Object> grant : myActiveGrants(userId)) {
            if (!id.equals(str(grant.get("docId")))) continue;
            Map<String, Object> d = grantedDoc(grant);
            if (d != null) {
                Map<String, Object> r = new LinkedHashMap<>(d);
                String role = str(grant.get("role"));
                r.put("permission", role == null ? "viewer" : role);
                r.put("readOnly", !"editor".equals(role));
                r.put("granted", true);
                r.put("ownerName", str(grant.get("ownerName")));
                return r;
            }
        }
        throw new BusinessException(404, "文档不存在");
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
        if (idx < 0) {
            // 文档不在自己名下：若是被授权的只读文档，明确拒绝修改；否则不存在。
            for (Map<String, Object> grant : myActiveGrants(userId)) {
                if (id.equals(str(grant.get("docId"))) && grantedDoc(grant) != null) {
                    throw new BusinessException(403, "这是别人分享给你的只读文档，无法修改");
                }
            }
            throw new BusinessException(404, "文档不存在");
        }
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

    // ================================================================== 权限申请 / 审批（跨用户）
    /**
     * 收件箱：我收到的访问申请（别人发给我、待我审批的）。
     * 授权记录（kind=grant）不在这里展示；申请人查自己的状态走「我的申请」。
     */
    public List<Object> listAccessRequests(Long userId, boolean inbox) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> r = asMap(o);
            if ("request".equals(str(r.get("kind")))) {
                // owner 视角：别人发给我的申请
                out.add(r);
            }
        }
        out.sort((a, b) -> str(asMap(b).get("createdAt")).compareTo(str(asMap(a).get("createdAt"))));
        return out;
    }

    /** 我发起的申请及其状态（含通过/拒绝/已授权），供申请者查看进度。 */
    public List<Object> listMyAccessRequests(Long userId) {
        List<Object> out = new ArrayList<>();
        for (Object o : store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS)) {
            Map<String, Object> r = asMap(o);
            if ("my_request".equals(str(r.get("kind")))) out.add(r);
        }
        out.sort((a, b) -> str(asMap(b).get("createdAt")).compareTo(str(asMap(a).get("createdAt"))));
        return out;
    }

    /** 文档元信息（供申请页展示标题与所有者；不含正文，不判断是否已授权）。 */
    public Map<String, Object> docMeta(String docId) {
        if (docId == null) throw new BusinessException(400, "缺少文档 id");
        Long owner = store.findOwnerOfDoc(docId);
        if (owner == null) throw new BusinessException(404, "文档不存在或链接已失效");
        String title = "";
        for (Object o : store.readColl(owner, EditorStore.Coll.DOCS)) {
            Map<String, Object> d = asMap(o);
            if (docId.equals(str(d.get("id")))) { title = str(d.get("title")) == null ? "" : str(d.get("title")); break; }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", docId); r.put("title", title); r.put("ownerId", owner);
        return r;
    }

    /**
     * 发起访问申请：按 docId 全局定位文档所有者，把申请写进所有者的收件箱。
     * 同时给申请者写一条 my_request 便于跟踪状态。
     */
    public Map<String, Object> addAccessRequest(Long userId, Map<String, Object> body, String username) {
        String docId = str(body.get("docId"));
        if (docId == null) throw new BusinessException(400, "缺少文档 id");
        Long owner = store.findOwnerOfDoc(docId);
        if (owner == null) throw new BusinessException(404, "文档不存在或链接已失效");
        if (owner.longValue() == userId.longValue()) throw new BusinessException(400, "这是你自己的文档，无需申请");

        // 已经拥有访问权限（自己是 owner 或已有 grant）则无需申请
        for (Map<String, Object> grant : myActiveGrants(userId)) {
            if (docId.equals(str(grant.get("docId")))) throw new BusinessException(400, "你已拥有该文档的访问权限");
        }

        // 取文档标题
        String docTitle = "";
        for (Object o : store.readColl(owner, EditorStore.Coll.DOCS)) {
            Map<String, Object> d = asMap(o);
            if (docId.equals(str(d.get("id")))) { docTitle = str(d.get("title")) == null ? "" : str(d.get("title")); break; }
        }
        String role = body.get("role") != null ? str(body.get("role")) : "viewer";
        if (!"editor".equals(role)) role = "viewer";
        String msg = str(body.get("message"));
        msg = msg == null ? "" : (msg.length() > 300 ? msg.substring(0, 300) : msg);

        // 防重复：所有者收件箱里是否已有该申请人对该文档的待处理申请
        String applicantKey = String.valueOf(userId);
        for (Object o : store.readColl(owner, EditorStore.Coll.ACCESS_REQUESTS)) {
            Map<String, Object> r = asMap(o);
            if ("request".equals(str(r.get("kind"))) && docId.equals(str(r.get("docId")))
                    && applicantKey.equals(str(r.get("applicantId"))) && "pending".equals(str(r.get("status")))) {
                throw new BusinessException(400, "你已申请过，请等待文档所有者审批");
            }
        }

        String reqId = newId();
        String now = nowISO();
        String ownerName = ownerName(owner);

        // 1) 写进所有者的收件箱
        List<Object> ownerReqs = store.readColl(owner, EditorStore.Coll.ACCESS_REQUESTS);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "request");
        item.put("id", reqId); item.put("docId", docId); item.put("docTitle", docTitle);
        item.put("applicant", username != null ? username : applicantKey);
        item.put("applicantId", userId); item.put("ownerId", owner); item.put("ownerName", ownerName);
        item.put("role", role); item.put("message", msg);
        item.put("status", "pending"); item.put("handledBy", null); item.put("handledAt", null);
        item.put("createdAt", now);
        ownerReqs.add(item);
        store.writeColl(owner, EditorStore.Coll.ACCESS_REQUESTS, ownerReqs);

        // 2) 给申请者写一条状态跟踪记录
        List<Object> myReqs = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("kind", "my_request");
        mine.put("id", reqId); mine.put("docId", docId); mine.put("docTitle", docTitle);
        mine.put("ownerId", owner); mine.put("ownerName", ownerName);
        mine.put("applicantId", userId); mine.put("applicant", username != null ? username : applicantKey);
        mine.put("role", role); mine.put("message", msg);
        mine.put("status", "pending"); mine.put("createdAt", now);
        myReqs.add(mine);
        store.writeColl(userId, EditorStore.Coll.ACCESS_REQUESTS, myReqs);

        // 3) 通知所有者
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "access_request"); note.put("docId", docId);
        note.put("docTitle", docTitle); note.put("actor", username);
        note.put("text", (username != null ? username : "有人") + " 申请访问《" + docTitle + "》");
        addNotification(owner, note);

        return item;
    }

    /** 从 users 表取用户名（尽力而为，取不到回退为 id 字符串）。 */
    private String ownerName(Long ownerId) {
        try {
            org.springframework.jdbc.core.JdbcTemplate jdbc = jdbcTemplate;
            if (jdbc != null) {
                List<String> names = jdbc.queryForList(
                        "SELECT username FROM users WHERE id=?", String.class, ownerId);
                if (!names.isEmpty()) return names.get(0);
            }
        } catch (Exception ignore) {}
        return String.valueOf(ownerId);
    }

    /**
     * 审批：只有文档所有者（收到申请的一方）能处理。
     * 通过时给申请者写入授权记录（kind=grant，status=active），此后申请者可读写守门内的文档。
     */
    public Map<String, Object> decideAccessRequest(Long userId, String id, boolean approve, String username) {
        List<Object> list = store.readColl(userId, EditorStore.Coll.ACCESS_REQUESTS);
        Map<String, Object> target = null;
        for (Object o : list) {
            Map<String, Object> r = asMap(o);
            if (id.equals(str(r.get("id"))) && "request".equals(str(r.get("kind")))
                    && "pending".equals(str(r.get("status")))) { target = r; break; }
        }
        if (target == null) throw new BusinessException(404, "申请不存在或已处理");

        String decision = approve ? "approved" : "rejected";
        String now = nowISO();
        target.put("status", decision);
        target.put("handledBy", username); target.put("handledAt", now);
        store.writeColl(userId, EditorStore.Coll.ACCESS_REQUESTS, list);

        Long applicantId = parseLong(target.get("applicantId"));
        String docId = str(target.get("docId"));
        String docTitle = str(target.get("docTitle"));
        String role = str(target.get("role"));
        if (role == null) role = "viewer";

        if (applicantId != null) {
            List<Object> applicantReqs = store.readColl(applicantId, EditorStore.Coll.ACCESS_REQUESTS);

            // 同步申请者的状态跟踪记录
            for (Object o : applicantReqs) {
                Map<String, Object> mine = asMap(o);
                if ("my_request".equals(str(mine.get("kind"))) && id.equals(str(mine.get("id")))) {
                    mine.put("status", decision); mine.put("handledAt", now); mine.put("handledBy", username);
                    break;
                }
            }

            if (approve) {
                // 写入授权记录：申请者可用此记录读取所有者的文档
                Map<String, Object> grant = new LinkedHashMap<>();
                grant.put("kind", "grant");
                grant.put("id", newId());
                grant.put("docId", docId); grant.put("docTitle", docTitle);
                grant.put("ownerId", userId); grant.put("ownerName", username != null ? username : String.valueOf(userId));
                grant.put("granteeId", applicantId);
                grant.put("role", role);
                grant.put("status", "active");
                grant.put("grantedAt", now); grant.put("requestId", id);
                applicantReqs.add(grant);
            }
            store.writeColl(applicantId, EditorStore.Coll.ACCESS_REQUESTS, applicantReqs);

            // 通知申请者
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("type", "access_decision"); note.put("docId", docId); note.put("docTitle", docTitle);
            note.put("actor", username);
            note.put("text", approve ? "你申请访问的《" + docTitle + "》已获批准" : "你申请访问的《" + docTitle + "》被拒绝");
            addNotification(applicantId, note);
        }

        return target;
    }

    /** 所有者撤销授权：清理自己收件箱里的申请 + 申请者名下的 grant。 */
    public Map<String, Object> revokeAccess(Long userId, String grantOrDocId, String applicantIdStr) {
        Long applicantId = parseLong(applicantIdStr);
        if (applicantId == null) throw new BusinessException(400, "缺少被授权用户");
        List<Object> applicantReqs = store.readColl(applicantId, EditorStore.Coll.ACCESS_REQUESTS);
        boolean changed = false;
        Iterator<Object> it = applicantReqs.iterator();
        while (it.hasNext()) {
            Map<String, Object> r = asMap(it.next());
            if ("grant".equals(str(r.get("kind"))) && userId.longValue() == parseLongDefault(r.get("ownerId"))
                    && (grantOrDocId.equals(str(r.get("id"))) || grantOrDocId.equals(str(r.get("docId"))))) {
                it.remove(); changed = true;
            }
        }
        if (changed) store.writeColl(applicantId, EditorStore.Coll.ACCESS_REQUESTS, applicantReqs);
        Map<String, Object> r = new LinkedHashMap<>(); r.put("ok", true); r.put("revoked", changed); return r;
    }

    private static long parseLongDefault(Object v) {
        Long l = parseLong(v); return l == null ? -1L : l;
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
