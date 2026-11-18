package com.example.website.editor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 学城编辑器 ACL 判定移植正确性测试。用例对照 worker/auth.js 的真实语义：
 * 继承链、inherit/propagate 双开关、防环、可见性、管理员不越权。
 */
class EditorAclTests {

    private EditorAcl acl;
    private Map<String, Object> alice, bob, carol;

    @BeforeEach
    void setUp() {
        acl = new EditorAcl();
        alice = user("1");
        bob = user("2");
        carol = user("3");
    }

    private Map<String, Object> user(String id) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", id);
        u.put("username", "u" + id);
        return u;
    }

    private Map<String, Object> doc(String id, String parentId, String ownerId,
                                    String visibility, boolean inherit, boolean propagate,
                                    Object... memberPairs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("parentId", parentId);
        d.put("ownerId", ownerId);
        Map<String, Object> members = new LinkedHashMap<>();
        for (int i = 0; i < memberPairs.length; i += 2) members.put((String) memberPairs[i], memberPairs[i + 1]);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("visibility", visibility);
        a.put("members", members);
        a.put("inherit", inherit);
        a.put("propagate", propagate);
        d.put("acl", a);
        return d;
    }

    @Test
    void ownerHasFullControl() {
        Map<String, Object> d = doc("d1", null, "1", "private", true, true);
        List<Map<String, Object>> list = Collections.singletonList(d);
        assertEquals("owner", acl.roleOf(alice, d, list));
        assertTrue(acl.canView(alice, d, list));
        assertTrue(acl.canEdit(alice, d, list));
        assertTrue(acl.canManage(alice, d, list));
    }

    @Test
    void strangerCannotSeePrivateDoc() {
        Map<String, Object> d = doc("d1", null, "1", "private", true, true);
        List<Map<String, Object>> list = Collections.singletonList(d);
        assertFalse(acl.canView(bob, d, list));
        assertNull(acl.roleOf(bob, d, list));
    }

    @Test
    void loggedInVisibilityLetsAnyUserViewButNotEdit() {
        Map<String, Object> d = doc("d1", null, "1", "logged-in", true, true);
        List<Map<String, Object>> list = Collections.singletonList(d);
        assertTrue(acl.canView(bob, d, list));
        assertFalse(acl.canEdit(bob, d, list));
        assertFalse(acl.canManage(bob, d, list));
    }

    @Test
    void memberRolesMapToCapabilities() {
        Map<String, Object> d = doc("d1", null, "1", "private", true, true, "2", "viewer");
        List<Map<String, Object>> list = Collections.singletonList(d);
        assertTrue(acl.canView(bob, d, list));
        assertFalse(acl.canEdit(bob, d, list));

        ((Map<String, Object>) ((Map<String, Object>) d.get("acl")).get("members")).put("2", "editor");
        assertTrue(acl.canEdit(bob, d, list));
        assertFalse(acl.canManage(bob, d, list));

        ((Map<String, Object>) ((Map<String, Object>) d.get("acl")).get("members")).put("2", "manager");
        assertTrue(acl.canManage(bob, d, list));
    }

    @Test
    void childInheritsParentAclWhenBothSwitchesOn() {
        Map<String, Object> parent = doc("p", null, "1", "private", true, true, "2", "editor");
        Map<String, Object> child = doc("c", "p", "1", "private", true, true);
        List<Map<String, Object>> list = Arrays.asList(parent, child);
        // 子文档有效 ACL 来自父：bob 是 editor
        assertEquals("editor", acl.roleOf(bob, child, list));
        assertTrue(acl.canEdit(bob, child, list));
        // 有效所有者仍是父所有者
        assertEquals("1", acl.effectiveOwner(child, list));
    }

    @Test
    void inheritFalseStopsPropagationToSelf() {
        Map<String, Object> parent = doc("p", null, "1", "private", true, true, "2", "editor");
        Map<String, Object> child = doc("c", "p", "1", "private", false, true);
        List<Map<String, Object>> list = Arrays.asList(parent, child);
        assertNull(acl.roleOf(bob, child, list));
        assertFalse(acl.canView(bob, child, list));
    }

    @Test
    void propagateFalseOnParentStopsInheritance() {
        Map<String, Object> parent = doc("p", null, "1", "private", true, false, "2", "editor");
        Map<String, Object> child = doc("c", "p", "1", "private", true, true);
        List<Map<String, Object>> list = Arrays.asList(parent, child);
        assertNull(acl.roleOf(bob, child, list));
    }

    @Test
    void aclCycleDoesNotHang() {
        Map<String, Object> a = doc("a", "b", "1", "private", true, true);
        Map<String, Object> b = doc("b", "a", "1", "private", true, true);
        List<Map<String, Object>> list = Arrays.asList(a, b);
        // 有环保护，应正常返回而不栈溢出
        assertNotNull(acl.effectiveAcl(a, list));
        assertNotNull(acl.effectiveOwner(a, list));
    }

    @Test
    void sanitizeAclDropsUnknownRolesAndUsers() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("2", "editor");   // 合法用户
        members.put("9", "editor");   // 不存在用户
        members.put("3", "superuser"); // 非法角色
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("visibility", "bogus");
        input.put("members", members);

        List<Map<String, Object>> users = Arrays.asList(user("1"), user("2"));
        Map<String, Object> clean = acl.sanitizeAcl(input, users);
        assertEquals("private", clean.get("visibility"));
        Map<String, Object> cm = (Map<String, Object>) clean.get("members");
        assertEquals(1, cm.size());
        assertEquals("editor", cm.get("2"));
    }
}
