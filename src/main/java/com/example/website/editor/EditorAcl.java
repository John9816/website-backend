package com.example.website.editor;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 学城编辑器文档 ACL 判定，逐行移植自 worker/auth.js（normalizeAcl/aclSource/
 * effectiveAcl/effectiveOwner/roleOf/canView/canEdit/canManage/sanitizeAcl）。
 *
 * <p>用户 id 统一为字符串（website 用户数字 id 的字符串形式），与 KV 数据一致。
 */
@Component
public class EditorAcl {

    private static final int MAX_ACL_DEPTH = 64;
    private static final List<String> ACL_ROLES = Arrays.asList("manager", "editor", "viewer");
    private static final List<String> VISIBILITIES = Arrays.asList("private", "logged-in", "link");

    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizeAcl(Map<String, Object> doc) {
        Map<String, Object> a = (doc != null && doc.get("acl") instanceof Map)
                ? (Map<String, Object>) doc.get("acl") : Collections.emptyMap();
        Object vis = a.get("visibility");
        String visibility = vis != null && VISIBILITIES.contains(vis.toString()) ? vis.toString() : "private";
        Map<String, Object> members = new LinkedHashMap<>();
        if (a.get("members") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) a.get("members")).entrySet()) {
                if (e.getValue() != null && ACL_ROLES.contains(e.getValue().toString())) {
                    members.put(String.valueOf(e.getKey()), e.getValue().toString());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visibility", visibility);
        out.put("members", members);
        out.put("inherit", !Boolean.FALSE.equals(a.get("inherit")));
        out.put("propagate", !Boolean.FALSE.equals(a.get("propagate")));
        return out;
    }

    public Map<String, Object> aclSource(Map<String, Object> doc, List<Map<String, Object>> list) {
        Map<String, Object> cur = doc;
        Set<Object> seen = new HashSet<>();
        int depth = 0;
        while (cur != null && depth < MAX_ACL_DEPTH && !seen.contains(cur.get("id"))) {
            seen.add(cur.get("id"));
            Map<String, Object> own = normalizeAcl(cur);
            Object pid = cur.get("parentId");
            if (Boolean.FALSE.equals(own.get("inherit")) || pid == null) break;
            Map<String, Object> parent = find(list, pid);
            if (parent == null || Boolean.FALSE.equals(normalizeAcl(parent).get("propagate"))) break;
            cur = parent;
            depth++;
        }
        return cur == null ? doc : cur;
    }

    public Map<String, Object> effectiveAcl(Map<String, Object> doc, List<Map<String, Object>> list) {
        return effectiveAcl(doc, list, new HashSet<>(), 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> effectiveAcl(Map<String, Object> doc, List<Map<String, Object>> list,
                                             Set<Object> seen, int depth) {
        Map<String, Object> own = normalizeAcl(doc);
        if (depth >= MAX_ACL_DEPTH || seen.contains(doc.get("id"))) return own;
        seen.add(doc.get("id"));
        Object pid = doc.get("parentId");
        if (Boolean.FALSE.equals(own.get("inherit")) || pid == null) return own;
        Map<String, Object> parent = find(list, pid);
        if (parent == null || Boolean.FALSE.equals(normalizeAcl(parent).get("propagate"))) return own;
        return effectiveAcl(parent, list, seen, depth + 1);
    }

    public Object effectiveOwner(Map<String, Object> doc, List<Map<String, Object>> list) {
        Map<String, Object> src = aclSource(doc, list);
        return src == null ? null : src.get("ownerId");
    }

    @SuppressWarnings("unchecked")
    public String roleOf(Map<String, Object> user, Map<String, Object> doc, List<Map<String, Object>> list) {
        if (user == null || doc == null) return null;
        Object uid = user.get("id");
        Object owner = effectiveOwner(doc, list);
        if (owner != null && String.valueOf(owner).equals(String.valueOf(uid))) return "owner";
        Map<String, Object> acl = effectiveAcl(doc, list);
        Object membersObj = acl.get("members");
        if (membersObj instanceof Map && uid != null && ((Map<?, ?>) membersObj).containsKey(String.valueOf(uid))) {
            return (String) ((Map<?, ?>) membersObj).get(String.valueOf(uid));
        }
        return null;
    }

    public boolean canManage(Map<String, Object> user, Map<String, Object> doc, List<Map<String, Object>> list) {
        String r = roleOf(user, doc, list);
        return "owner".equals(r) || "manager".equals(r);
    }

    public boolean canEdit(Map<String, Object> user, Map<String, Object> doc, List<Map<String, Object>> list) {
        String r = roleOf(user, doc, list);
        return "owner".equals(r) || "manager".equals(r) || "editor".equals(r);
    }

    public boolean canView(Map<String, Object> user, Map<String, Object> doc, List<Map<String, Object>> list) {
        if (user == null || doc == null) return false;
        String r = roleOf(user, doc, list);
        if (r != null) return true;
        Object v = effectiveAcl(doc, list).get("visibility");
        return "logged-in".equals(v) || "link".equals(v);
    }

    /** 规范化前端提交的 acl，只保留合法角色且存在于用户集合的成员。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sanitizeAcl(Object input, List<Map<String, Object>> users) {
        Map<String, Object> src = input instanceof Map ? (Map<String, Object>) input : Collections.emptyMap();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("acl", src);
        Map<String, Object> acl = normalizeAcl(doc);
        Map<String, Object> members = new LinkedHashMap<>();
        if (src.get("members") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) src.get("members")).entrySet()) {
                Object role = e.getValue();
                String uid = String.valueOf(e.getKey());
                if (role != null && ACL_ROLES.contains(role.toString())
                        && users.stream().anyMatch(u -> uid.equals(String.valueOf(u.get("id"))))) {
                    members.put(uid, role.toString());
                }
            }
        }
        acl.put("members", members);
        return acl;
    }

    public Map<String, Object> find(List<Map<String, Object>> list, Object id) {
        if (id == null) return null;
        for (Map<String, Object> d : list) {
            if (id.equals(d.get("id")) || String.valueOf(id).equals(String.valueOf(d.get("id")))) return d;
        }
        return null;
    }

    public int findIndex(List<Map<String, Object>> list, Object id) {
        if (id == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (String.valueOf(id).equals(String.valueOf(list.get(i).get("id")))) return i;
        }
        return -1;
    }
}
