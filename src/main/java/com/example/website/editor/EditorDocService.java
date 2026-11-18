package com.example.website.editor;

import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 学城编辑器 · 文档/模板/回收站/分享业务。逐项移植自 worker/index.js 与 worker/auth.js。
 * 所有操作跑在 EditorKvStore 的单个事务快照里（锁内 load→modify→flush）。
 */
@Service
@RequiredArgsConstructor
public class EditorDocService {

    private final EditorKvStore store;
    private final EditorSupport support;
    private final EditorAcl acl;

    private static final List<String> PREF_KEYS = Arrays.asList("favorite", "pinned", "pinnedAt", "lastVisitedAt");

    // ---------------- 列表 / 单文档投影 ----------------

    /** GET /docs —— 可见文档投影 + 为不可见后代补占位，保持树结构不泄露内容。 */
    public List<Object> listDocs(User user) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> prefs = tx.prefs();
            List<Map<String, Object>> users = tx.users();
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> d : docs) byId.put(str(d.get("id")), d);
            Map<String, Map<String, Object>> usersById = new LinkedHashMap<>();
            for (Map<String, Object> u : users) usersById.put(str(u.get("id")), u);

            Map<String, Object> userMap = support.editorUser(user);
            String uid = support.userId(user);

            Set<String> visible = new LinkedHashSet<>();
            for (Map<String, Object> d : docs) if (acl.canView(userMap, d, docs)) visible.add(str(d.get("id")));

            // 为可见文档的不可见祖先补占位
            Map<String, Map<String, Object>> placeholders = new LinkedHashMap<>();
            for (String id : visible) {
                Map<String, Object> cur = byId.get(id);
                Set<String> seen = new LinkedHashSet<>();
                int depth = 0;
                while (cur != null && depth < 64 && !seen.contains(str(cur.get("id")))) {
                    seen.add(str(cur.get("id")));
                    Object pidObj = cur.get("parentId");
                    String pid = pidObj == null ? null : str(pidObj);
                    if (pid == null || !byId.containsKey(pid)) break;
                    Map<String, Object> p = byId.get(pid);
                    if (visible.contains(pid)) break;
                    if (!placeholders.containsKey(pid)) {
                        Map<String, Object> ph = new LinkedHashMap<>();
                        ph.put("id", pid);
                        ph.put("title", "受限文档");
                        ph.put("parentId", p.get("parentId") != null && byId.containsKey(str(p.get("parentId"))) ? str(p.get("parentId")) : null);
                        ph.put("placeholder", true);
                        ph.put("canEdit", false);
                        ph.put("canManage", false);
                        ph.put("favorite", false);
                        ph.put("pinned", false);
                        ph.put("pinnedAt", null);
                        ph.put("lastVisitedAt", null);
                        ph.put("updatedAt", null);
                        ph.put("createdAt", null);
                        placeholders.put(pid, ph);
                    }
                    cur = p;
                    depth++;
                }
            }

            List<Object> out = new ArrayList<>();
            for (Map<String, Object> d : docs) {
                String id = str(d.get("id"));
                if (visible.contains(id)) {
                    Map<String, Object> proj = project(d, docs, usersById, userMap, prefs, uid, false);
                    out.add(proj);
                } else if (placeholders.containsKey(id)) {
                    out.add(placeholders.get(id));
                }
            }
            return out;
        });
    }

    /** GET /docs/:id —— 403 时带回申请权限所需的最小上下文。 */
    public Object getDoc(User user, String id) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, id);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canView(userMap, d, docs)) {
                // 返回带 needRequest 的上下文，由 Controller 转成 403 + data
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("needRequest", true);
                ctx.put("docId", id);
                ctx.put("title" , d.get("title") != null ? d.get("title") : "未命名文档");
                Map<String, Object> owner = null;
                Object ownerId = acl.effectiveOwner(d, docs);
                for (Map<String, Object> u : tx.users()) if (ownerId != null && str(ownerId).equals(str(u.get("id")))) owner = u;
                ctx.put("ownerName", owner != null ? (owner.get("displayName") != null ? owner.get("displayName") : owner.get("username")) : "文档所有者");
                // 已在申请的回显
                for (Map<String, Object> r : tx.accessRequests()) {
                    if (id.equals(str(r.get("docId"))) && support.userId(user).equals(str(r.get("userId"))) && "pending".equals(r.get("status"))) {
                        Map<String, Object> req = new LinkedHashMap<>();
                        req.put("id", r.get("id"));
                        req.put("status", r.get("status"));
                        req.put("createdAt", r.get("createdAt"));
                        req.put("grantedRole", r.get("grantedRole"));
                        ctx.put("request", req);
                        break;
                    }
                }
                throw new EditorForbidden(ctx, "没有查看该文档的权限");
            }
            Map<String, Map<String, Object>> usersById = new LinkedHashMap<>();
            for (Map<String, Object> u : tx.users()) usersById.put(str(u.get("id")), u);
            Map<String, Object> out = project(d, docs, usersById, userMap, tx.prefs(), support.userId(user), true);
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> project(Map<String, Object> d, List<Map<String, Object>> docs,
                                        Map<String, Map<String, Object>> usersById, Map<String, Object> userMap,
                                        Map<String, Object> prefs, String uid, boolean full) {
        Map<String, Object> r = new LinkedHashMap<>(d);
        r.put("canEdit", acl.canEdit(userMap, d, docs));
        r.put("canManage", acl.canManage(userMap, d, docs));
        r.put("visibility", acl.effectiveAcl(d, docs).get("visibility"));
        r.put("inherit", acl.normalizeAcl(d).get("inherit"));
        String role = acl.roleOf(userMap, d, docs);
        boolean isShared = role != null && !"owner".equals(role);
        r.put("ownedByMe", acl.effectiveOwner(d, docs) != null && str(acl.effectiveOwner(d, docs)).equals(uid));
        r.put("sharedWithMe", isShared);
        if (full) {
            Object ownerId = acl.effectiveOwner(d, docs);
            Map<String, Object> ow = ownerId == null ? null : usersById.get(str(ownerId));
            r.put("ownerName", isShared && ow != null ? (ow.get("displayName") != null ? ow.get("displayName") : ow.get("username")) : null);
        }
        // 成员/ownerId 只给管理者
        if (!Boolean.TRUE.equals(r.get("canManage"))) {
            r.remove("acl");
            r.remove("ownerId");
        } else if (acl.normalizeAcl(d).containsKey("inherit")) {
            r.put("acl", acl.normalizeAcl(d));
        }
        mergePrefs(r, uid, str(d.get("id")), prefs);
        // 列表摘要
        if (!full) {
            r.put("shared", r.get("canManage") != null && Boolean.TRUE.equals(r.get("canManage"))
                    && d.get("share") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) d.get("share")).get("enabled")));
            Object content = d.get("content");
            r.put("excerpt", excerpt(content, 120));
            r.remove("content");
        }
        return r;
    }

    // ---------------- 新建 / 保存 / 局部更新 / 删除 ----------------

    public Object createDoc(User user, Map<String, Object> body) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Object parentId = body.get("parentId");
            if (parentId != null) {
                Map<String, Object> parent = acl.find(docs, parentId);
                if (parent == null) throw new BusinessException(400, "目标父文档不存在");
                if (!acl.canEdit(userMap, parent, docs)) throw new BusinessException(403, "没有在该文档下新建子文档的权限");
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", support.newId());
            Object title = body.get("title");
            doc.put("title", (title == null ? "未命名文档" : str(title)).substring(0, Math.min(120, str(title == null ? "" : title).length())));
            doc.put("content", body.get("content"));
            doc.put("parentId", parentId);
            doc.put("favorite", false);
            doc.put("pinned", false);
            doc.put("pinnedAt", null);
            doc.put("lastVisitedAt", null);
            doc.put("share", null);
            doc.put("ownerId", support.userId(user));
            Map<String, Object> aclObj = new LinkedHashMap<>();
            aclObj.put("visibility", "private");
            aclObj.put("members", new LinkedHashMap<String, Object>());
            aclObj.put("inherit", true);
            aclObj.put("propagate", true);
            doc.put("acl", aclObj);
            doc.put("createdAt", support.nowIso());
            doc.put("updatedAt", support.nowIso());
            docs.add(doc);
            tx.mark("docs");
            return doc;
        });
    }

    /** PUT/PATCH /docs/:id —— 偏好字段走 prefs，结构字段需 canManage。 */
    public Object saveDoc(User user, String id, Map<String, Object> body) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            int i = acl.findIndex(docs, id);
            if (i < 0) throw new BusinessException(404, "文档不存在");
            Map<String, Object> d = docs.get(i);
            Map<String, Object> userMap = support.editorUser(user);

            List<String> keys = new ArrayList<>();
            for (String k : body.keySet()) {
                if (k.equals("title") || k.equals("content") || k.equals("parentId") || k.equals("acl") || PREF_KEYS.contains(k)) keys.add(k);
            }
            boolean prefOnly = !keys.isEmpty();
            for (String k : keys) if (!PREF_KEYS.contains(k)) prefOnly = false;

            if (prefOnly) {
                if (!acl.canView(userMap, d, docs)) throw new BusinessException(403, "没有查看该文档的权限");
                writePref(tx, support.userId(user), id, body);
                return decorate(d, docs, userMap, tx);
            }
            if (!acl.canEdit(userMap, d, docs)) throw new BusinessException(403, "没有编辑该文档的权限");
            boolean structural = body.containsKey("parentId") || body.containsKey("acl");
            if (structural && !acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有文档所有者可以调整结构或权限");

            boolean edited = false;
            if (body.get("title") instanceof String) {
                String t = (String) body.get("title");
                d.put("title", t.substring(0, Math.min(120, t.length())));
                edited = true;
            }
            if (body.containsKey("content")) { d.put("content", body.get("content")); edited = true; }
            if (body.containsKey("acl")) {
                Map<String, Object> prevAcl = acl.normalizeAcl(d);
                Object prevVis = prevAcl.get("visibility");
                Map<String, Object> san = acl.sanitizeAcl(body.get("acl"), tx.users());
                d.put("acl", san);
                if ("link".equals(san.get("visibility"))) {
                    Map<String, Object> cur = d.get("share") instanceof Map ? (Map<String, Object>) d.get("share") : null;
                    Map<String, Object> sh = new LinkedHashMap<>();
                    sh.put("token", cur != null && cur.get("token") != null ? cur.get("token") : support.rndB64(12));
                    sh.put("enabled", true);
                    sh.put("views", cur != null && cur.get("views") != null ? cur.get("views") : 0);
                    sh.put("createdAt", cur != null && cur.get("createdAt") != null ? cur.get("createdAt") : support.nowIso());
                    sh.put("updatedAt", support.nowIso());
                    d.put("share", sh);
                } else if ("link".equals(prevVis) && d.get("share") instanceof Map) {
                    Map<String, Object> sh = new LinkedHashMap<>((Map<String, Object>) d.get("share"));
                    sh.put("enabled", false);
                    sh.put("updatedAt", support.nowIso());
                    d.put("share", sh);
                }
            }
            if (body.containsKey("parentId")) {
                Object pidObj = body.get("parentId");
                String pid = pidObj == null ? null : str(pidObj);
                if (pid != null && pid.equals(id)) throw new BusinessException(400, "不能移动到自身之下");
                if (pid != null) {
                    Map<String, Object> parent = acl.find(docs, pid);
                    if (parent == null) throw new BusinessException(400, "目标文档不存在");
                    if (!acl.canEdit(userMap, parent, docs)) throw new BusinessException(403, "没有移动到该文档下的权限");
                    // 防环
                    String cur = pid;
                    Set<String> seen = new LinkedHashSet<>();
                    while (cur != null) {
                        if (cur.equals(id)) throw new BusinessException(400, "不能移动到自身或其子文档下");
                        if (!seen.add(cur)) break;
                        Map<String, Object> p = acl.find(docs, cur);
                        cur = p == null || p.get("parentId") == null ? null : str(p.get("parentId"));
                    }
                }
                d.put("parentId", pid);
            }
            // 混合偏好字段
            Map<String, Object> prefPatch = new LinkedHashMap<>();
            for (String k : PREF_KEYS) if (body.containsKey(k)) prefPatch.put(k, body.get(k));
            if (!prefPatch.isEmpty()) writePref(tx, support.userId(user), id, prefPatch);
            if (edited) d.put("updatedAt", support.nowIso());
            tx.mark("docs");
            return decorate(d, docs, userMap, tx);
        });
    }

    private Map<String, Object> decorate(Map<String, Object> d, List<Map<String, Object>> docs,
                                         Map<String, Object> userMap, EditorKvStore.Tx tx) {
        Map<String, Map<String, Object>> usersById = new LinkedHashMap<>();
        for (Map<String, Object> u : tx.users()) usersById.put(str(u.get("id")), u);
        return project(d, docs, usersById, userMap, tx.prefs(), str(userMap.get("id")), true);
    }

    /** DELETE /docs/:id —— 级联软删除到回收站。 */
    public void deleteDoc(User user, String id) {
        store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, id);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有文档所有者可以删除");
            Set<String> toDelete = new LinkedHashSet<>();
            toDelete.add(id);
            boolean changed;
            do {
                changed = false;
                for (Map<String, Object> x : docs) {
                    Object pid = x.get("parentId");
                    if (pid != null && toDelete.contains(str(pid)) && !toDelete.contains(str(x.get("id")))) {
                        toDelete.add(str(x.get("id")));
                        changed = true;
                    }
                }
            } while (changed);
            for (String did : toDelete) {
                Map<String, Object> sub = acl.find(docs, did);
                if (sub == null || !acl.canManage(userMap, sub, docs)) throw new BusinessException(403, "子文档中存在无权删除的内容");
            }
            List<Map<String, Object>> removed = new ArrayList<>();
            List<Map<String, Object>> remaining = new ArrayList<>();
            for (Map<String, Object> x : docs) (toDelete.contains(str(x.get("id"))) ? removed : remaining).add(x);

            Map<String, Object> interSnap = new LinkedHashMap<>();
            Map<String, Object> inter = tx.interactions();
            for (String did : toDelete) if (inter.containsKey(did)) interSnap.put(did, inter.get(did));
            Map<String, Object> visitorSnap = new LinkedHashMap<>();
            Map<String, Object> visitors = tx.visitors();
            for (Map<String, Object> x : removed) {
                if (x.get("share") instanceof Map) {
                    Object tk = ((Map<?, ?>) x.get("share")).get("token");
                    if (tk != null && visitors.containsKey(str(tk))) visitorSnap.put(str(tk), visitors.get(str(tk)));
                }
            }
            Map<String, Object> prefSnap = new LinkedHashMap<>();
            Map<String, Object> prefs = tx.prefs();
            for (Object uidKey : prefs.keySet()) {
                Object up = prefs.get(uidKey);
                if (!(up instanceof Map)) continue;
                Map<String, Object> userPrefs = (Map<String, Object>) up;
                for (String did : toDelete) if (userPrefs.containsKey(did)) {
                    Map<String, Object> inner = (Map<String, Object>) prefSnap.computeIfAbsent(str(uidKey), k -> new LinkedHashMap<String, Object>());
                    inner.put(did, userPrefs.get(did));
                }
            }
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("id", support.rndHex(9));
            batch.put("deletedAt", support.nowIso());
            batch.put("deletedBy", support.userId(user));
            batch.put("ownerId", support.userId(user));
            batch.put("rootId", id);
            batch.put("rootTitle", d.get("title") != null ? d.get("title") : "未命名文档");
            batch.put("docs", removed);
            batch.put("interactions", interSnap);
            batch.put("visitors", visitorSnap);
            batch.put("prefs", prefSnap);

            Map<String, Object> trash = tx.trash();
            List<Map<String, Object>> batches = (List<Map<String, Object>>) trash.get("batches");
            batches.add(0, batch);
            // 清理在线关联数据
            for (String did : toDelete) inter.remove(did);
            for (Object tk : visitorSnap.keySet()) visitors.remove(tk);
            for (Object uidKey : prefSnap.keySet()) {
                Object up = prefs.get(uidKey);
                if (up instanceof Map) for (String did : toDelete) ((Map<String, Object>) up).remove(did);
            }
            tx.docs().clear(); tx.docs().addAll(remaining);
            tx.mark("trash"); tx.mark("docs"); tx.mark("interactions"); tx.mark("visitors"); tx.mark("prefs");
            return null;
        });
    }

    // ---------------- 回收站 ----------------

    public List<Object> trashList(User user) {
        return store.in(tx -> {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> b : (List<Map<String, Object>>) tx.trash().get("batches")) {
                if (support.userId(user).equals(str(b.get("deletedBy"))) || support.userId(user).equals(str(b.get("ownerId")))) {
                    out.add(briefBatch(b));
                }
            }
            return out;
        });
    }

    public void trashRestore(User user, String batchId) {
        store.in(tx -> {
            List<Map<String, Object>> batches = (List<Map<String, Object>>) tx.trash().get("batches");
            int idx = -1;
            for (int i = 0; i < batches.size(); i++) if (batchId.equals(str(batches.get(i).get("id")))) idx = i;
            if (idx < 0) throw new BusinessException(404, "回收站批次不存在");
            Map<String, Object> b = batches.get(idx);
            if (!support.userId(user).equals(str(b.get("ownerId")))) throw new BusinessException(403, "无权恢复该批次");
            for (Map<String, Object> d : (List<Map<String, Object>>) b.get("docs")) {
                if (acl.find(tx.docs(), d.get("id")) != null) throw new BusinessException(409, "文档 id 冲突，无法恢复");
            }
            tx.docs().addAll((List<Map<String, Object>>) b.get("docs"));
            if (b.get("interactions") instanceof Map) tx.interactions().putAll((Map<String, Object>) b.get("interactions"));
            if (b.get("visitors") instanceof Map) tx.visitors().putAll((Map<String, Object>) b.get("visitors"));
            if (b.get("prefs") instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) b.get("prefs")).entrySet()) {
                    Map<String, Object> userPrefs = (Map<String, Object>) tx.prefs().computeIfAbsent(str(e.getKey()), k -> new LinkedHashMap<String, Object>());
                    if (e.getValue() instanceof Map) userPrefs.putAll((Map<String, Object>) e.getValue());
                }
            }
            batches.remove(idx);
            tx.mark("docs"); tx.mark("trash"); tx.mark("interactions"); tx.mark("visitors"); tx.mark("prefs");
            return null;
        });
    }

    public void trashPurge(User user, String batchId) {
        store.in(tx -> {
            List<Map<String, Object>> batches = (List<Map<String, Object>>) tx.trash().get("batches");
            int idx = -1;
            for (int i = 0; i < batches.size(); i++) if (batchId.equals(str(batches.get(i).get("id")))) idx = i;
            if (idx < 0) throw new BusinessException(404, "回收站批次不存在");
            if (!support.userId(user).equals(str(batches.get(idx).get("ownerId")))) throw new BusinessException(403, "无权操作该批次");
            batches.remove(idx);
            tx.mark("trash");
            return null;
        });
    }

    public void trashClear(User user) {
        store.in(tx -> {
            List<Map<String, Object>> batches = (List<Map<String, Object>>) tx.trash().get("batches");
            batches.removeIf(b -> support.userId(user).equals(str(b.get("ownerId"))) || support.userId(user).equals(str(b.get("deletedBy"))));
            tx.mark("trash");
            return null;
        });
    }

    private Map<String, Object> briefBatch(Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>(b);
        Object docsObj = b.get("docs");
        if (docsObj instanceof List) m.put("count", ((List<?>) docsObj).size());
        m.remove("docs");
        m.remove("prefs");
        m.remove("visitors");
        m.remove("interactions");
        return m;
    }

    // ---------------- 模板 ----------------

    public List<Object> templates(User user) {
        return store.in(tx -> {
            List<Object> out = new ArrayList<>();
            String uid = support.userId(user);
            for (Map<String, Object> t : tx.templates()) {
                if (uid.equals(str(t.get("ownerId"))) || Boolean.TRUE.equals(t.get("isBuiltin"))) out.add(new LinkedHashMap<>(t));
            }
            return out;
        });
    }

    public Object createTemplate(User user, Map<String, Object> body) {
        return store.in(tx -> {
            Map<String, Object> t = new LinkedHashMap<>(body);
            t.put("id", support.newId());
            t.put("ownerId", support.userId(user));
            t.put("createdAt", support.nowIso());
            t.put("updatedAt", support.nowIso());
            tx.templates().add(t);
            tx.mark("templates");
            return t;
        });
    }

    public Object updateTemplate(User user, String id, Map<String, Object> body) {
        return store.in(tx -> {
            Map<String, Object> t = acl.find(tx.templates(), id);
            if (t == null) throw new BusinessException(404, "模板不存在");
            if (!support.userId(user).equals(str(t.get("ownerId")))) throw new BusinessException(403, "无权修改该模板");
            for (Map.Entry<String, Object> e : body.entrySet()) {
                if (!e.getKey().equals("id") && !e.getKey().equals("ownerId")) t.put(e.getKey(), e.getValue());
            }
            t.put("updatedAt", support.nowIso());
            tx.mark("templates");
            return t;
        });
    }

    public void deleteTemplate(User user, String id) {
        store.in(tx -> {
            Map<String, Object> t = acl.find(tx.templates(), id);
            if (t == null) throw new BusinessException(404, "模板不存在");
            if (!support.userId(user).equals(str(t.get("ownerId")))) throw new BusinessException(403, "无权删除该模板");
            tx.templates().remove(t);
            tx.mark("templates");
            return null;
        });
    }

    // ---------------- 分享 ----------------

    public Map<String, Object> shareState(User user, String docId) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有管理者可查看分享状态");
            Map<String, Object> out = new LinkedHashMap<>();
            Object sh = d.get("share");
            out.put("share", sh);
            out.put("visibility", acl.normalizeAcl(d).get("visibility"));
            return out;
        });
    }

    public Map<String, Object> shareDoc(User user, String docId) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有管理者可开启分享");
            Map<String, Object> cur = d.get("share") instanceof Map ? (Map<String, Object>) d.get("share") : null;
            Map<String, Object> sh = new LinkedHashMap<>();
            sh.put("token", cur != null && cur.get("token") != null ? cur.get("token") : support.rndB64(12));
            sh.put("enabled", true);
            sh.put("views", cur != null && cur.get("views") != null ? cur.get("views") : 0);
            sh.put("createdAt", cur != null && cur.get("createdAt") != null ? cur.get("createdAt") : support.nowIso());
            sh.put("updatedAt", support.nowIso());
            d.put("share", sh);
            tx.mark("docs");
            return sh;
        });
    }

    public void unshareDoc(User user, String docId) {
        store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有管理者可关闭分享");
            if (d.get("share") instanceof Map) {
                Map<String, Object> sh = new LinkedHashMap<>((Map<String, Object>) d.get("share"));
                sh.put("enabled", false);
                sh.put("updatedAt", support.nowIso());
                d.put("share", sh);
                tx.mark("docs");
            }
            return null;
        });
    }

    /** 匿名只读分享内容。 */
    public Map<String, Object> getShared(String token) {
        return store.in(tx -> {
            for (Map<String, Object> d : tx.docs()) {
                if (d.get("share") instanceof Map) {
                    Map<String, Object> sh = (Map<String, Object>) d.get("share");
                    if (Boolean.TRUE.equals(sh.get("enabled")) && token.equals(str(sh.get("token")))) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("title", d.get("title"));
                        out.put("content", d.get("content"));
                        out.put("createdAt", d.get("createdAt"));
                        out.put("updatedAt", d.get("updatedAt"));
                        out.put("views", sh.get("views") != null ? sh.get("views") : 0);
                        out.put("unique", 0);
                        out.put("online", 0);
                        // 浏览量 +1
                        sh.put("views", (sh.get("views") instanceof Number ? ((Number) sh.get("views")).longValue() : 0) + 1);
                        tx.mark("docs");
                        return out;
                    }
                }
            }
            throw new BusinessException(404, "分享链接不存在或已被关闭");
        });
    }

    // ---------------- 用户查询（供 @提及 / 分享成员选择）----------------

    public List<Object> searchUsers(User user, String q) {
        return store.in(tx -> {
            String needle = q == null ? "" : q.toLowerCase();
            List<Object> out = new ArrayList<>();
            for (User u : support.userRepositorySafe().findAll()) {
                String un = u.getUsername() == null ? "" : u.getUsername().toLowerCase();
                if (needle.isEmpty() || un.contains(needle)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(u.getId()));
                    m.put("username", u.getUsername());
                    m.put("displayName", u.getUsername());
                    m.put("avatarColor", support.avatarColorSafe(u.getUsername()));
                    out.add(m);
                }
            }
            return out;
        });
    }

    // ---------------- helpers ----------------

    private void mergePrefs(Map<String, Object> proj, String uid, String docId, Map<String, Object> prefs) {
        Map<String, Object> up = prefs.get(uid) instanceof Map ? (Map<String, Object>) prefs.get(uid) : null;
        Map<String, Object> p = up == null ? null : (up.get(docId) instanceof Map ? (Map<String, Object>) up.get(docId) : null);
        proj.put("favorite", p != null && Boolean.TRUE.equals(p.get("favorite")));
        proj.put("pinned", p != null && Boolean.TRUE.equals(p.get("pinned")));
        proj.put("pinnedAt", p != null ? p.get("pinnedAt") : null);
        proj.put("lastVisitedAt", p != null ? p.get("lastVisitedAt") : null);
    }

    private void writePref(EditorKvStore.Tx tx, String uid, String docId, Map<String, Object> patch) {
        Map<String, Object> prefs = tx.prefs();
        Map<String, Object> up = (Map<String, Object>) prefs.computeIfAbsent(uid, k -> new LinkedHashMap<String, Object>());
        Map<String, Object> cur = up.get(docId) instanceof Map ? (Map<String, Object>) up.get(docId) : new LinkedHashMap<>();
        if (!cur.containsKey("favorite")) cur.put("favorite", false);
        if (!cur.containsKey("pinned")) cur.put("pinned", false);
        if (!cur.containsKey("pinnedAt")) cur.put("pinnedAt", null);
        if (!cur.containsKey("lastVisitedAt")) cur.put("lastVisitedAt", null);
        for (String k : PREF_KEYS) if (patch.containsKey(k)) cur.put(k, patch.get(k));
        up.put(docId, cur);
        tx.mark("prefs");
    }

    private String excerpt(Object content, int max) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(content, sb, max * 2);
        String normalized = sb.toString().replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private void walk(Object nd, StringBuilder sb, int max) {
        if (nd == null || sb.length() >= max) return;
        if (nd instanceof String) { sb.append(nd); return; }
        if (nd instanceof List) { for (Object o : (List<?>) nd) walk(o, sb, max); return; }
        if (nd instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) nd;
            if ("text".equals(m.get("type"))) sb.append(m.get("text") == null ? "" : m.get("text"));
            else if (m.get("content") != null) walk(m.get("content"), sb, max);
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** 403 且带业务上下文（申请权限），由 Controller 转成 HTTP 403 + data。 */
    public static class EditorForbidden extends RuntimeException {
        private final Object data;
        public EditorForbidden(Object data, String msg) { super(msg); this.data = data; }
        public Object getData() { return data; }
    }
}
