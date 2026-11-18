package com.example.website.editor;

import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * 编辑器业务公共助手：当前用户解析、用户 id 归一化、id 生成、时间、响应投影。
 *
 * <p>用户体系策略：编辑器直接复用 website 的 users 表与 JWT。KV 里的所有用户 id 统一用
 * website 的数字 id 字符串（如 "33"），所以 ownerId / acl.members 与 website 用户天然对齐，
 * 前端拿到的 user.id 也是数字字符串，无需任何转换层。
 */
@Component
@RequiredArgsConstructor
public class EditorSupport {

    private final UserRepository userRepository;
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 从 request 取当前用户（JwtAuthFilter 已注入 userId）。未登录抛 401。 */
    public User requireUser(HttpServletRequest request) {
        Object attr = request.getAttribute("userId");
        if (!(attr instanceof Number)) throw new BusinessException(401, "请先登录");
        Long id = ((Number) attr).longValue();
        return userRepository.findById(id).orElseThrow(() -> new BusinessException(401, "登录已失效"));
    }

    /** 尝试取当前用户，未登录返回 null（用于 /auth/me 这类匿名可调用接口）。 */
    public User currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute("userId");
        if (!(attr instanceof Number)) return null;
        return userRepository.findById(((Number) attr).longValue()).orElse(null);
    }

    /** website 用户 -> 编辑器前端期望的 user 对象（字段与 auth.js publicUser 对齐）。 */
    public Map<String, Object> editorUser(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(u.getId()));
        m.put("username", u.getUsername());
        m.put("displayName", u.getUsername());
        m.put("role", roleOf(u));
        m.put("avatarColor", avatarColor(u.getUsername()));
        m.put("createdAt", u.getCreatedAt() == null ? nowIso() : u.getCreatedAt().toString());
        m.put("disabled", !u.isEnabled());
        return m;
    }

    public String roleOf(User u) {
        String role = u.getRole();
        if (role == null) return "user";
        String r = role.startsWith("ROLE_") ? role.substring(5) : role;
        return "ADMIN".equalsIgnoreCase(r) ? "admin" : "user";
    }

    public boolean isAdmin(User u) {
        return "admin".equals(roleOf(u));
    }

    public String userId(User u) {
        return String.valueOf(u.getId());
    }

    /** 生成 editor 风格的短随机 id（16 位 hex），用于 doc/template/request 等。 */
    public String newId() {
        return rndHex(8);
    }

    public String rndHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0xf]).append(HEX[x & 0xf]);
        }
        return sb.toString();
    }

    public String rndB64(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public String nowIso() {
        return Instant.now().toString();
    }

    public com.example.website.repository.UserRepository userRepositorySafe() {
        return userRepository;
    }

    public String avatarColorSafe(String s) {
        return avatarColor(s);
    }

    private String avatarColor(String s) {
        String[] palette = {"#f0736a", "#5b8def", "#48a88a", "#c586c0", "#d69e2e", "#38a3a5", "#d97757"};
        if (s == null || s.isEmpty()) return palette[0];
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) & 0x7fffffff;
        return palette[h % palette.length];
    }

    /** 从 KV 的用户集合里按 website 用户 id 找到（或补建）编辑器用户投影，便于共享文档显示名。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ensureEditorUser(EditorKvStore.Tx tx, User u) {
        List<Map<String, Object>> users = tx.users();
        String uid = userId(u);
        for (Map<String, Object> e : users) {
            if (uid.equals(String.valueOf(e.get("id")))) return e;
        }
        Map<String, Object> e = editorUser(u);
        users.add(e);
        tx.mark("users");
        return e;
    }
}
