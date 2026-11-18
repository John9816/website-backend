package com.example.website.editor;

import com.example.website.common.ApiResponse;
import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 学城编辑器 API —— 对接 website-backend，路径前缀 /api/editor。
 *
 * <p>前端 xuecheng-editor 原用 /editor/api + Cookie 会话；迁入后：
 *   - 路径：/editor/api/**  ->  /api/editor/**（website 的 Pages Functions 只代理 /api/*）
 *   - 鉴权：Cookie 会话  ->  website JWT（JwtAuthFilter 注入 request.userId）
 *   - 响应：{code:0,message:"ok",data}（复用 ApiResponse）与前端契约完全一致。
 */
@RestController
@RequestMapping("/api/editor")
@RequiredArgsConstructor
public class EditorController {

    private final EditorSupport support;
    private final EditorDocService docService;
    private final EditorSocialService socialService;
    private final EditorUploadService uploadService;

    private User user(HttpServletRequest req) { return support.requireUser(req); }
    private String visitorKey(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        String ip = req.getHeader("X-Forwarded-For");
        String raw = (ip == null ? req.getRemoteAddr() : ip) + "|" + (ua == null ? "" : ua);
        return Integer.toHexString(raw.hashCode());
    }

    // ---------------- 健康 ----------------
    @GetMapping("/health")
    public ApiResponse<Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("service", "xuecheng-editor (Java)");
        return ApiResponse.ok(m);
    }

    // ---------------- 鉴权 / 身份 ----------------
    /** 未登录返回 200 + null，与前端 api.auth.me 的匿名探测语义一致。 */
    @GetMapping("/auth/me")
    public ApiResponse<Object> me(HttpServletRequest req) {
        User u = support.currentUser(req);
        if (u == null) return ApiResponse.ok(null);
        Map<String, Object> m = support.editorUser(u);
        m.put("canManageMcp", support.isAdmin(u));
        return ApiResponse.ok(m);
    }

    /** 编辑器内的登录入口：复用 website 账号体系，校验后返回 editor 用户对象。
     *  前端拿到后仍需走 website 的登录流程设置 JWT；这里仅作为兼容桥接返回用户信息。 */
    @PostMapping("/auth/login")
    public ApiResponse<Object> loginBridge(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        // 若请求已带 JWT（已登录），直接返回当前用户；否则提示通过主站登录。
        User u = support.currentUser(req);
        if (u != null) return ApiResponse.ok(support.editorUser(u));
        throw new BusinessException(401, "请先在网站登录后使用编辑器");
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Object> logoutBridge() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ApiResponse.ok(m);
    }

    @GetMapping("/auth/bootstrap-status")
    public ApiResponse<Object> bootstrapStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("needBootstrapCode", false);
        m.put("bootstrapConfigured", true);
        return ApiResponse.ok(m);
    }

    // ---------------- 文档 ----------------
    @GetMapping("/docs")
    public ApiResponse<Object> listDocs(HttpServletRequest req) {
        return ApiResponse.ok(docService.listDocs(user(req)));
    }

    @PostMapping("/docs")
    public ApiResponse<Object> createDoc(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(docService.createDoc(user(req), body));
    }

    @GetMapping("/docs/{id}")
    public ResponseEntity<ApiResponse<Object>> getDoc(HttpServletRequest req, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(docService.getDoc(user(req), id)));
        } catch (EditorDocService.EditorForbidden e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, e.getMessage()));
        }
    }

    @PutMapping("/docs/{id}")
    public ApiResponse<Object> saveDoc(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(docService.saveDoc(user(req), id, body));
    }

    @PatchMapping("/docs/{id}")
    public ApiResponse<Object> patchDoc(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(docService.saveDoc(user(req), id, body));
    }

    @DeleteMapping("/docs/{id}")
    public ApiResponse<Object> deleteDoc(HttpServletRequest req, @PathVariable String id) {
        docService.deleteDoc(user(req), id);
        return ApiResponse.ok((Object) null);
    }

    // ---------------- 回收站 ----------------
    @GetMapping("/trash")
    public ApiResponse<Object> trashList(HttpServletRequest req) {
        return ApiResponse.ok(docService.trashList(user(req)));
    }

    @PostMapping("/trash/{batchId}/restore")
    public ApiResponse<Object> trashRestore(HttpServletRequest req, @PathVariable String batchId) {
        docService.trashRestore(user(req), batchId);
        return ApiResponse.ok((Object) null);
    }

    @DeleteMapping("/trash/{batchId}")
    public ApiResponse<Object> trashPurge(HttpServletRequest req, @PathVariable String batchId) {
        docService.trashPurge(user(req), batchId);
        return ApiResponse.ok((Object) null);
    }

    @DeleteMapping("/trash")
    public ApiResponse<Object> trashClear(HttpServletRequest req) {
        docService.trashClear(user(req));
        return ApiResponse.ok((Object) null);
    }

    // ---------------- 模板 ----------------
    @GetMapping("/templates")
    public ApiResponse<Object> templates(HttpServletRequest req) {
        return ApiResponse.ok(docService.templates(user(req)));
    }

    @PostMapping("/templates")
    public ApiResponse<Object> createTemplate(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(docService.createTemplate(user(req), body));
    }

    @PatchMapping("/templates/{id}")
    public ApiResponse<Object> updateTemplate(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(docService.updateTemplate(user(req), id, body));
    }

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Object> deleteTemplate(HttpServletRequest req, @PathVariable String id) {
        docService.deleteTemplate(user(req), id);
        return ApiResponse.ok((Object) null);
    }

    // ---------------- 分享 ----------------
    @GetMapping("/docs/{id}/share")
    public ApiResponse<Object> shareState(HttpServletRequest req, @PathVariable String id) {
        return ApiResponse.ok(docService.shareState(user(req), id));
    }

    @PostMapping("/docs/{id}/share")
    public ApiResponse<Object> shareDoc(HttpServletRequest req, @PathVariable String id) {
        return ApiResponse.ok(docService.shareDoc(user(req), id));
    }

    @DeleteMapping("/docs/{id}/share")
    public ApiResponse<Object> unshareDoc(HttpServletRequest req, @PathVariable String id) {
        docService.unshareDoc(user(req), id);
        return ApiResponse.ok((Object) null);
    }

    @GetMapping("/share/{token}")
    public ApiResponse<Object> getShared(@PathVariable String token) {
        return ApiResponse.ok(docService.getShared(token));
    }

    // ---------------- 用户搜索 ----------------
    @GetMapping("/users")
    public ApiResponse<Object> users(HttpServletRequest req, @RequestParam(defaultValue = "") String q) {
        return ApiResponse.ok(docService.searchUsers(user(req), q));
    }

    // ---------------- 通知 ----------------
    @GetMapping("/notifications")
    public ApiResponse<Object> notifications(HttpServletRequest req) {
        return ApiResponse.ok(socialService.notifications(user(req)));
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<Object> notifReadAll(HttpServletRequest req) {
        socialService.readAll(user(req));
        return ApiResponse.ok((Object) null);
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Object> notifRead(HttpServletRequest req, @PathVariable String id) {
        socialService.readOne(user(req), id);
        return ApiResponse.ok((Object) null);
    }

    @PostMapping("/docs/{id}/mention")
    public ApiResponse<Object> mention(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.mention(user(req), id, body));
    }

    // ---------------- 权限申请 ----------------
    @PostMapping("/docs/{id}/access-request")
    public ApiResponse<Object> createAccessRequest(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.createAccessRequest(user(req), id, body));
    }

    @GetMapping("/access-requests")
    public ApiResponse<Object> myAccessRequests(HttpServletRequest req) {
        return ApiResponse.ok(socialService.myAccessRequests(user(req)));
    }

    @GetMapping("/access-requests/inbox")
    public ApiResponse<Object> accessInbox(HttpServletRequest req) {
        return ApiResponse.ok(socialService.accessInbox(user(req)));
    }

    @PostMapping("/access-requests/{rid}")
    public ApiResponse<Object> decideAccess(HttpServletRequest req, @PathVariable String rid, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.decideAccessRequest(user(req), rid, body));
    }

    @DeleteMapping("/access-requests/{rid}")
    public ApiResponse<Object> cancelAccess(HttpServletRequest req, @PathVariable String rid) {
        socialService.cancelAccessRequest(user(req), rid);
        return ApiResponse.ok((Object) null);
    }

    // ---------------- ACL ----------------
    @GetMapping("/docs/{id}/acl")
    public ApiResponse<Object> getAcl(HttpServletRequest req, @PathVariable String id) {
        return ApiResponse.ok(socialService.getAcl(user(req), id));
    }

    @PutMapping("/docs/{id}/acl")
    public ApiResponse<Object> setAcl(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.setAcl(user(req), id, body));
    }

    // ---------------- 投票 / 问卷 ----------------
    @GetMapping("/docs/{id}/interactions")
    public ApiResponse<Object> getInteractions(HttpServletRequest req, @PathVariable String id,
                                               @RequestParam(defaultValue = "0") int detail) {
        return ApiResponse.ok(socialService.getInteractions(id, visitorKey(req), detail == 1));
    }

    @PostMapping("/docs/{id}/interactions")
    public ApiResponse<Object> submitInteraction(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.submitInteraction(id, visitorKey(req), body));
    }

    @GetMapping("/share/{token}/interactions")
    public ApiResponse<Object> getShareInteractions(HttpServletRequest req, @PathVariable String token) {
        return ApiResponse.ok(socialService.getInteractions("share:" + token, visitorKey(req), false));
    }

    @PostMapping("/share/{token}/interactions")
    public ApiResponse<Object> submitShareInteraction(HttpServletRequest req, @PathVariable String token, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(socialService.submitInteraction("share:" + token, visitorKey(req), body));
    }

    // ---------------- 在线头像 ----------------
    @PostMapping("/docs/{id}/presence")
    public ApiResponse<Object> presence(HttpServletRequest req, @PathVariable String id) {
        return ApiResponse.ok(socialService.presence(id, visitorKey(req)));
    }

    @DeleteMapping("/docs/{id}/presence")
    public ApiResponse<Object> presenceLeave(HttpServletRequest req, @PathVariable String id) {
        socialService.presenceLeave(id, visitorKey(req));
        return ApiResponse.ok((Object) null);
    }

    @PostMapping("/share/{token}/presence")
    public ApiResponse<Object> sharePresence(HttpServletRequest req, @PathVariable String token) {
        return ApiResponse.ok(socialService.presence("share:" + token, visitorKey(req)));
    }

    @DeleteMapping("/share/{token}/presence")
    public ApiResponse<Object> sharePresenceLeave(HttpServletRequest req, @PathVariable String token) {
        socialService.presenceLeave("share:" + token, visitorKey(req));
        return ApiResponse.ok((Object) null);
    }

    // ---------------- 分享统计（占位） ----------------
    @PostMapping("/share/{token}/heartbeat")
    public ApiResponse<Object> shareHeartbeat(@PathVariable String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ApiResponse.ok(m);
    }

    @GetMapping("/share/{token}/stats")
    public ApiResponse<Object> shareStats(@PathVariable String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", 0); m.put("views", 0); m.put("unique", 0);
        return ApiResponse.ok(m);
    }

    // ---------------- 上传 ----------------
    @PostMapping("/upload")
    public ApiResponse<Object> upload(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(uploadService.uploadImage(str(body.get("dataUrl")), str(body.get("name"))));
    }

    @PostMapping("/upload/audio")
    public ApiResponse<Object> uploadAudio(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(uploadService.uploadAudio(str(body.get("dataUrl")), str(body.get("name"))));
    }

    @GetMapping("/media/{filename}")
    public ResponseEntity<byte[]> serveMedia(@PathVariable String filename) {
        return uploadService.serve(filename);
    }

    // ---------------- MCP 元数据（占位，普通用户只读） ----------------
    @GetMapping("/mcp/meta")
    public ApiResponse<Object> mcpMeta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", false);
        return ApiResponse.ok(m);
    }

    @GetMapping("/mcp/servers")
    public ApiResponse<Object> mcpServers() { return ApiResponse.ok(new ArrayList<>()); }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
