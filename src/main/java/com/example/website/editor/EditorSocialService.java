package com.example.website.editor;

import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学城编辑器 · 社交/协作功能：通知、权限申请、ACL 管理、@提及、投票问卷、在线头像。
 */
@Service
@RequiredArgsConstructor
public class EditorSocialService {

    private final EditorKvStore store;
    private final EditorSupport support;
    private final EditorAcl acl;

    private static final Map<String, String> ROLE_LABELS = new LinkedHashMap<>();
    static {
        ROLE_LABELS.put("viewer", "仅查看");
        ROLE_LABELS.put("editor", "可编辑");
        ROLE_LABELS.put("manager", "可管理");
        ROLE_LABELS.put("owner", "所有者");
    }
    private static final List<String> ACL_ROLES = Arrays.asList("viewer", "editor", "manager");
    private static final Pattern MENTION = Pattern.compile("@([^\\s@，。、,.;:!！?？（）()\\[\\]【】\"“”'‘’]+)");

    // ---------------- 通知 ----------------

    public Map<String, Object> notifications(User user) {
        return store.in(tx -> {
            String uid = support.userId(user);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> n : tx.notifications()) if (uid.equals(str(n.get("userId")))) list.add(n);
            list.sort((a, b) -> str(b.get("createdAt")).compareTo(str(a.get("createdAt"))));
            long unread = list.stream().filter(n -> !Boolean.TRUE.equals(n.get("read"))).count();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", list.subList(0, Math.min(50, list.size())));
            out.put("unread", unread);
            return out;
        });
    }

    public void readAll(User user) {
        store.in(tx -> {
            String uid = support.userId(user);
            for (Map<String, Object> n : tx.notifications()) if (uid.equals(str(n.get("userId")))) n.put("read", true);
            tx.mark("notifications");
            return null;
        });
    }

    public void readOne(User user, String id) {
        store.in(tx -> {
            String uid = support.userId(user);
            for (Map<String, Object> n : tx.notifications()) {
                if (id.equals(str(n.get("id"))) && uid.equals(str(n.get("userId")))) {
                    n.put("read", true);
                    tx.mark("notifications");
                    return null;
                }
            }
            throw new BusinessException(404, "通知不存在");
        });
    }

    /** 内部：追加通知（去重）。 */
    private void addNotification(EditorKvStore.Tx tx, String userId, Map<String, Object> n) {
        if (userId == null) return;
        List<Map<String, Object>> list = tx.notifications();
        for (Map<String, Object> x : list) {
            if (!Boolean.TRUE.equals(x.get("read")) && userId.equals(str(x.get("userId")))
                    && str(x.get("docId")).equals(str(n.get("docId")))
                    && str(x.get("type")).equals(str(n.get("type")))
                    && str(x.get("actorId")).equals(str(n.get("actorId")))) return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", support.rndHex(8));
        item.put("userId", userId);
        item.put("type", n.get("type"));
        item.put("actorId", n.get("actorId"));
        item.put("actorName", n.get("actorName") != null ? n.get("actorName") : "");
        item.put("docId", n.get("docId"));
        item.put("docTitle", n.get("docTitle") != null ? n.get("docTitle") : "");
        item.put("role", n.get("role"));
        item.put("message", n.get("message") != null ? str(n.get("message")).substring(0, Math.min(200, str(n.get("message")).length())) : "");
        item.put("read", false);
        item.put("createdAt", support.nowIso());
        list.add(item);
        if (list.size() > 100) list.subList(0, list.size() - 100).clear();
        tx.mark("notifications");
    }

    // ---------------- 权限申请 ----------------

    public Object createAccessRequest(User user, String docId, Map<String, Object> body) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (acl.canView(userMap, d, docs)) throw new BusinessException(400, "你已有该文档的访问权限，无需申请");
            String uid = support.userId(user);
            for (Map<String, Object> r : tx.accessRequests()) {
                if (docId.equals(str(r.get("docId"))) && uid.equals(str(r.get("userId"))) && "pending".equals(r.get("status"))) {
                    throw new BusinessException(409, "已有待处理的申请，请等待所有者处理");
                }
            }
            String role = "editor".equals(body.get("role")) ? "editor" : "viewer";
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", support.rndHex(8));
            rec.put("docId", docId);
            rec.put("userId", uid);
            rec.put("role", role);
            rec.put("message", body.get("message") == null ? "" : str(body.get("message")).substring(0, Math.min(200, str(body.get("message")).length())));
            rec.put("status", "pending");
            rec.put("createdAt", support.nowIso());
            rec.put("handledAt", null);
            rec.put("handledBy", null);
            rec.put("grantedRole", null);
            tx.accessRequests().add(rec);
            tx.mark("accessRequests");
            Object ownerId = acl.effectiveOwner(d, docs);
            if (ownerId != null && !str(ownerId).equals(uid)) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "access_request"); n.put("actorId", uid);
                n.put("actorName", userMap.get("displayName")); n.put("docId", docId);
                n.put("docTitle", d.get("title")); n.put("role", role); n.put("message", rec.get("message"));
                addNotification(tx, str(ownerId), n);
            }
            rec.put("roleLabel", ROLE_LABELS.get(role));
            return rec;
        });
    }

    public List<Object> myAccessRequests(User user) {
        return store.in(tx -> {
            String uid = support.userId(user);
            Map<String, Map<String, Object>> docsById = new LinkedHashMap<>();
            for (Map<String, Object> d : tx.docs()) docsById.put(str(d.get("id")), d);
            Map<String, Map<String, Object>> usersById = new LinkedHashMap<>();
            for (Map<String, Object> u : tx.users()) usersById.put(str(u.get("id")), u);
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> r : tx.accessRequests()) {
                if (!uid.equals(str(r.get("userId")))) continue;
                Map<String, Object> m = new LinkedHashMap<>(r);
                Map<String, Object> d = docsById.get(str(r.get("docId")));
                m.put("docTitle", d != null && d.get("title") != null ? d.get("title") : "未命名文档");
                Object ownerId = d == null ? null : acl.effectiveOwner(d, tx.docs());
                Map<String, Object> ow = ownerId == null ? null : usersById.get(str(ownerId));
                m.put("ownerName", ow != null ? (ow.get("displayName") != null ? ow.get("displayName") : ow.get("username")) : "文档所有者");
                m.put("roleLabel", ROLE_LABELS.get(str(r.get("role"))));
                out.add(m);
            }
            return out;
        });
    }

    public List<Object> accessInbox(User user) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Map<String, Object>> usersById = new LinkedHashMap<>();
            for (Map<String, Object> u : tx.users()) usersById.put(str(u.get("id")), u);
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> r : tx.accessRequests()) {
                if (!"pending".equals(r.get("status"))) continue;
                Map<String, Object> d = acl.find(docs, r.get("docId"));
                if (d == null || !acl.canManage(userMap, d, docs)) continue;
                Map<String, Object> u = usersById.get(str(r.get("userId")));
                if (u == null || Boolean.TRUE.equals(u.get("disabled"))) continue;
                Map<String, Object> src = acl.aclSource(d, docs);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.get("id")); m.put("docId", r.get("docId"));
                m.put("docTitle", d.get("title") != null ? d.get("title") : "未命名文档");
                m.put("userId", r.get("userId"));
                m.put("username", u.get("username"));
                m.put("displayName", u.get("displayName"));
                m.put("avatarColor", u.get("avatarColor"));
                m.put("role", r.get("role"));
                m.put("roleLabel", ROLE_LABELS.get(str(r.get("role"))));
                m.put("message", r.get("message"));
                m.put("createdAt", r.get("createdAt"));
                boolean scopeDoc = str(src.get("id")).equals(str(d.get("id")));
                m.put("scope", scopeDoc ? "doc" : "parent");
                m.put("sourceTitle", src.get("title") != null ? src.get("title") : "上级目录");
                out.add(m);
            }
            out.sort((a, b) -> str(((Map<?, ?>) b).get("createdAt")).compareTo(str(((Map<?, ?>) a).get("createdAt"))));
            return out;
        });
    }

    public Object decideAccessRequest(User user, String rid, Map<String, Object> body) {
        return store.in(tx -> {
            Map<String, Object> rec = null;
            for (Map<String, Object> r : tx.accessRequests()) if (rid.equals(str(r.get("id")))) { rec = r; break; }
            if (rec == null) throw new BusinessException(404, "申请不存在");
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, rec.get("docId"));
            if (d == null) throw new BusinessException(404, "文档不存在或已被删除");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有所有者或「可管理」成员可以审批申请");
            if (!"pending".equals(rec.get("status"))) throw new BusinessException(400, "该申请已处理");
            String action = str(body.get("action"));
            if (!"approve".equals(action) && !"reject".equals(action)) throw new BusinessException(400, "action 只能是 approve 或 reject");
            rec.put("handledAt", support.nowIso());
            rec.put("handledBy", support.userId(user));
            rec.put("status", "approve".equals(action) ? "approved" : "rejected");
            String scope = "doc";
            String sourceTitle = d.get("title") != null ? str(d.get("title")) : "当前文档";
            if ("approve".equals(action)) {
                Object roleObj = body.get("role");
                String role = roleObj != null && ACL_ROLES.contains(roleObj.toString()) ? roleObj.toString()
                        : (rec.get("role") != null ? str(rec.get("role")) : "viewer");
                Map<String, Object> src = acl.aclSource(d, docs);
                Map<String, Object> target = acl.find(docs, src.get("id"));
                if (target == null) throw new BusinessException(404, "权限来源文档不存在");
                Map<String, Object> normalized = acl.normalizeAcl(target);
                Map<String, Object> members = (Map<String, Object>) normalized.get("members");
                members.put(str(rec.get("userId")), role);
                target.put("acl", acl.sanitizeAcl(normalized, tx.users()));
                rec.put("grantedRole", role);
                scope = str(target.get("id")).equals(str(d.get("id"))) ? "doc" : "parent";
                sourceTitle = target.get("title") != null ? str(target.get("title")) : "上级目录";
                tx.mark("docs");
            }
            tx.mark("accessRequests");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "approve".equals(action) ? "access_approved" : "access_rejected");
            n.put("actorId", support.userId(user)); n.put("actorName", userMap.get("displayName"));
            n.put("docId", rec.get("docId")); n.put("docTitle", d.get("title"));
            n.put("role", rec.get("grantedRole"));
            addNotification(tx, str(rec.get("userId")), n);
            rec.put("scope", scope); rec.put("sourceTitle", sourceTitle);
            return rec;
        });
    }

    public void cancelAccessRequest(User user, String rid) {
        store.in(tx -> {
            Map<String, Object> rec = null;
            for (Map<String, Object> r : tx.accessRequests()) if (rid.equals(str(r.get("id")))) { rec = r; break; }
            if (rec == null) throw new BusinessException(404, "申请不存在");
            if (!support.userId(user).equals(str(rec.get("userId")))) throw new BusinessException(403, "只能撤回自己的申请");
            if (!"pending".equals(rec.get("status"))) throw new BusinessException(400, "该申请已处理，无法撤回");
            tx.accessRequests().remove(rec);
            tx.mark("accessRequests");
            return null;
        });
    }

    // ---------------- ACL ----------------

    public Map<String, Object> getAcl(User user, String docId) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有文档所有者可以管理权限");
            Map<String, Object> own = acl.normalizeAcl(d);
            Map<String, Object> eff = acl.effectiveAcl(d, docs);
            Map<String, Object> parent = d.get("parentId") == null ? null : acl.find(docs, d.get("parentId"));
            Map<String, Object> src = acl.aclSource(d, docs);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("own", own); out.put("effective", eff);
            out.put("ownerId", d.get("ownerId"));
            out.put("parentId", d.get("parentId"));
            out.put("parentTitle", parent != null && parent.get("title") != null ? parent.get("title") : null);
            out.put("canInherit", parent != null);
            long childCount = docs.stream().filter(x -> docId.equals(str(x.get("parentId")))).count();
            out.put("childCount", childCount);
            out.put("sourceId", src.get("id"));
            out.put("sourceTitle", src.get("title") != null ? src.get("title") : "未命名文档");
            out.put("visibility", eff.get("visibility"));
            out.put("shared", d.get("share") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) d.get("share")).get("enabled")));
            out.put("members", withRole(tx.users(), (Map<String, Object>) eff.get("members")));
            out.put("ownMembers", withRole(tx.users(), (Map<String, Object>) own.get("members")));
            return out;
        });
    }

    public Map<String, Object> setAcl(User user, String docId, Map<String, Object> body) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canManage(userMap, d, docs)) throw new BusinessException(403, "只有文档所有者可以管理权限");
            Object aclInput = body.containsKey("acl") ? body.get("acl") : body;
            Map<String, Object> san = acl.sanitizeAcl(aclInput, tx.users());
            d.put("acl", san);
            Object vis = san.get("visibility");
            if ("link".equals(vis)) {
                Map<String, Object> cur = d.get("share") instanceof Map ? (Map<String, Object>) d.get("share") : null;
                Map<String, Object> sh = new LinkedHashMap<>();
                sh.put("token", cur != null && cur.get("token") != null ? cur.get("token") : support.rndB64(12));
                sh.put("enabled", true); sh.put("views", cur != null && cur.get("views") != null ? cur.get("views") : 0);
                sh.put("createdAt", cur != null && cur.get("createdAt") != null ? cur.get("createdAt") : support.nowIso());
                sh.put("updatedAt", support.nowIso());
                d.put("share", sh);
            } else if (d.get("share") instanceof Map) {
                Map<String, Object> sh = new LinkedHashMap<>((Map<String, Object>) d.get("share"));
                sh.put("enabled", false); sh.put("updatedAt", support.nowIso());
                d.put("share", sh);
            }
            d.put("updatedAt", support.nowIso());
            tx.mark("docs");
            return getAclProjection(d, docs, tx);
        });
    }

    private Map<String, Object> getAclProjection(Map<String, Object> d, List<Map<String, Object>> docs, EditorKvStore.Tx tx) {
        Map<String, Object> own = acl.normalizeAcl(d);
        Map<String, Object> eff = acl.effectiveAcl(d, docs);
        Map<String, Object> src = acl.aclSource(d, docs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("own", own); out.put("effective", eff);
        out.put("ownerId", d.get("ownerId"));
        out.put("visibility", eff.get("visibility"));
        out.put("sourceId", src.get("id"));
        out.put("members", withRole(tx.users(), (Map<String, Object>) eff.get("members")));
        out.put("ownMembers", withRole(tx.users(), (Map<String, Object>) own.get("members")));
        return out;
    }

    private List<Object> withRole(List<Map<String, Object>> users, Map<String, Object> members) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> u : users) {
            Object role = members.get(str(u.get("id")));
            if (role == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(u);
            m.remove("hash"); m.remove("salt");
            m.put("role", role);
            out.add(m);
        }
        return out;
    }

    // ---------------- @提及 ----------------

    public Map<String, Object> mention(User user, String docId, Map<String, Object> body) {
        return store.in(tx -> {
            List<Map<String, Object>> docs = tx.docs();
            Map<String, Object> userMap = support.editorUser(user);
            Map<String, Object> d = acl.find(docs, docId);
            if (d == null) throw new BusinessException(404, "文档不存在");
            if (!acl.canView(userMap, d, docs)) throw new BusinessException(403, "无访问权限");
            String text = body.get("text") == null ? "" : str(body.get("text"));
            if (text.length() > 2000) text = text.substring(0, 2000);
            Matcher m = MENTION.matcher(text);
            Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
            for (Map<String, Object> u : tx.users()) {
                if (u.get("username") != null) byKey.putIfAbsent(str(u.get("username")).toLowerCase(), u);
                if (u.get("displayName") != null) byKey.putIfAbsent(str(u.get("displayName")).toLowerCase(), u);
            }
            Set<String> seen = new LinkedHashSet<>();
            List<Object> notified = new ArrayList<>();
            String uid = support.userId(user);
            while (m.find()) {
                String tk = m.group(1);
                Map<String, Object> target = byKey.get(tk.toLowerCase());
                if (target == null || uid.equals(str(target.get("id")))) continue;
                String tid = str(target.get("id"));
                if (!seen.add(tid)) continue;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "mention"); n.put("actorId", uid); n.put("actorName", userMap.get("displayName"));
                n.put("docId", docId); n.put("docTitle", d.get("title"));
                n.put("message", text.substring(0, Math.min(120, text.length())));
                addNotification(tx, tid, n);
                notified.add(tid);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("notified", notified);
            return out;
        });
    }

    // ---------------- 投票 / 问卷 ----------------

    public Object getInteractions(String docId, String visitorId, boolean detail) {
        return store.in(tx -> {
            Map<String, Object> docBlocks = (Map<String, Object>) tx.interactions().get(docId);
            Map<String, Object> out = new LinkedHashMap<>();
            if (docBlocks == null) return out;
            for (Map.Entry<String, Object> e : docBlocks.entrySet()) {
                out.put(e.getKey(), tally((Map<String, Object>) e.getValue(), visitorId, detail));
            }
            return out;
        });
    }

    public Object submitInteraction(String docId, String visitorId, Map<String, Object> body) {
        return store.in(tx -> {
            String blockId = body.get("blockId") == null ? "" : str(body.get("blockId"));
            if (blockId.length() > 40) blockId = blockId.substring(0, 40);
            if (blockId.isEmpty()) throw new BusinessException(400, "缺少 blockId");
            String type = "survey".equals(body.get("type")) ? "survey" : "poll";
            Map<String, Object> map = tx.interactions();
            Map<String, Object> docBlocks = (Map<String, Object>) map.computeIfAbsent(docId, k -> new LinkedHashMap<String, Object>());
            Map<String, Object> blk = (Map<String, Object>) docBlocks.computeIfAbsent(blockId, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("type", type); b.put("voters", new LinkedHashMap<String, Object>()); b.put("responses", new ArrayList<Object>());
                return b;
            });
            blk.put("type", type);
            if ("poll".equals(type)) {
                if (!"vote".equals(body.get("action")) || body.get("vote") == null) throw new BusinessException(400, "投票缺少选项");
                String vote = str(body.get("vote"));
                if (vote.length() > 40) vote = vote.substring(0, 40);
                Map<String, Object> voters = (Map<String, Object>) blk.get("voters");
                for (String optId : new ArrayList<>(voters.keySet())) {
                    List<String> vids = new ArrayList<>((List<String>) voters.get(optId));
                    vids.remove(visitorId);
                    if (vids.isEmpty() && !optId.equals(vote)) voters.remove(optId); else voters.put(optId, vids);
                }
                List<String> target = (List<String>) voters.computeIfAbsent(vote, k -> new ArrayList<String>());
                if (!target.contains(visitorId)) target.add(visitorId);
            } else {
                if (!"submit".equals(body.get("action"))) throw new BusinessException(400, "问卷缺少 submit 动作");
                List<Map<String, Object>> responses = (List<Map<String, Object>>) blk.get("responses");
                for (Map<String, Object> r : responses) if (visitorId.equals(str(r.get("vid")))) throw new BusinessException(409, "你已提交过该问卷");
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("id", support.newId()); resp.put("vid", visitorId);
                resp.put("answers", body.get("answers") instanceof Map ? body.get("answers") : new LinkedHashMap<String, Object>());
                resp.put("ts", support.nowIso());
                responses.add(resp);
            }
            tx.mark("interactions");
            return tally(blk, visitorId, true);
        });
    }

    private Map<String, Object> tally(Map<String, Object> blk, String visitorId, boolean detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = str(blk.get("type"));
        out.put("type", type);
        if ("poll".equals(type)) {
            Map<String, Object> voters = (Map<String, Object>) blk.get("voters");
            Map<String, Object> counts = new LinkedHashMap<>();
            int total = 0;
            for (Map.Entry<String, Object> e : voters.entrySet()) {
                int c = ((List<?>) e.getValue()).size();
                counts.put(e.getKey(), c);
                total += c;
            }
            out.put("counts", counts); out.put("total", total);
            String mine = null;
            for (Map.Entry<String, Object> e : voters.entrySet()) if (((List<?>) e.getValue()).contains(visitorId)) mine = e.getKey();
            out.put("mine", mine);
        } else {
            List<?> responses = (List<?>) blk.get("responses");
            out.put("count", responses.size());
            out.put("submitted", responses.stream().anyMatch(r -> visitorId.equals(str(((Map<?, ?>) r).get("vid")))));
            if (detail) out.put("responses", responses);
        }
        return out;
    }

    // ---------------- 在线头像（简化内存态）----------------

    private final Map<String, Map<String, Object>> presenceMemory = new LinkedHashMap<>();

    public Map<String, Object> presence(String docId, String visitorKey) {
        synchronized (presenceMemory) {
            Map<String, Object> doc = presenceMemory.computeIfAbsent(docId, k -> new LinkedHashMap<>());
            doc.put(visitorKey, System.currentTimeMillis());
            long cutoff = System.currentTimeMillis() - 60000;
            doc.entrySet().removeIf(e -> ((Number) e.getValue()).longValue() < cutoff);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("online", doc.size());
            out.put("keys", new ArrayList<>(doc.keySet()));
            return out;
        }
    }

    public void presenceLeave(String docId, String visitorKey) {
        synchronized (presenceMemory) {
            Map<String, Object> doc = presenceMemory.get(docId);
            if (doc != null) doc.remove(visitorKey);
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
