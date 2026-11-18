package com.example.website.module.editor.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.editor.application.EditorService;
import com.example.website.module.identity.application.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 学城文档认证接口。路径 /api/user/editor/**，由 SecurityConfig 的 /api/user/** authenticated 规则保护。
 * 用户隔离通过 JWT 中的 userId（request attribute）。
 */
@RestController
@RequestMapping("/api/user/editor")
@RequiredArgsConstructor
public class EditorController {

    private final EditorService service;
    private final UserScopeService userScopeService;

    private Long uid(HttpServletRequest r) { return userScopeService.requireAuthenticatedUserId(r); }
    private String uname(HttpServletRequest r) {
        Object v = r.getAttribute("username");
        return v == null ? String.valueOf(uid(r)) : String.valueOf(v);
    }

    // ---------------- 文档 ----------------
    @GetMapping("/docs")
    public ResponseEntity<?> listDocs(HttpServletRequest r) {
        return ResponseEntity.ok(ApiResponse.ok(service.listDocs(uid(r))));
    }

    @PostMapping("/docs")
    public ResponseEntity<?> createDoc(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.createDoc(uid(r), body == null ? new java.util.HashMap<>() : body)));
    }

    @GetMapping("/docs/{id}")
    public ResponseEntity<?> getDoc(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getDoc(uid(r), id)));
    }

    @PutMapping("/docs/{id}")
    public ResponseEntity<?> putDoc(HttpServletRequest r, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateDoc(uid(r), id, body == null ? new java.util.HashMap<>() : body)));
    }

    @PatchMapping("/docs/{id}")
    public ResponseEntity<?> patchDoc(HttpServletRequest r, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateDoc(uid(r), id, body == null ? new java.util.HashMap<>() : body)));
    }

    @DeleteMapping("/docs/{id}")
    public ResponseEntity<?> deleteDoc(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.deleteDoc(uid(r), id, null)));
    }

    // ---------------- 分享（设置端；公开读取在 EditorPublicController） ----------------
    @GetMapping("/docs/{id}/share")
    public ResponseEntity<?> getShare(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getShareState(uid(r), id)));
    }

    @PostMapping("/docs/{id}/share")
    public ResponseEntity<?> enableShare(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.enableShare(uid(r), id)));
    }

    @DeleteMapping("/docs/{id}/share")
    public ResponseEntity<?> disableShare(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.disableShare(uid(r), id)));
    }

    // ---------------- 模板 ----------------
    @GetMapping("/templates")
    public ResponseEntity<?> listTemplates(HttpServletRequest r) {
        return ResponseEntity.ok(ApiResponse.ok(service.listTemplates(uid(r))));
    }
    @GetMapping("/templates/{id}")
    public ResponseEntity<?> getTemplate(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getTemplate(uid(r), id)));
    }
    @PostMapping("/templates")
    public ResponseEntity<?> createTemplate(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.createTemplate(uid(r), body == null ? new java.util.HashMap<>() : body)));
    }
    @PatchMapping("/templates/{id}")
    public ResponseEntity<?> updateTemplate(HttpServletRequest r, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateTemplate(uid(r), id, body == null ? new java.util.HashMap<>() : body)));
    }
    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> removeTemplate(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.removeTemplate(uid(r), id)));
    }

    // ---------------- 回收站 ----------------
    @GetMapping("/trash")
    public ResponseEntity<?> listTrash(HttpServletRequest r) {
        return ResponseEntity.ok(ApiResponse.ok(service.listTrash(uid(r))));
    }
    @PostMapping("/trash/{id}")
    public ResponseEntity<?> restoreTrash(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.restoreTrash(uid(r), id)));
    }
    @DeleteMapping("/trash/{id}")
    public ResponseEntity<?> purgeTrash(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.purgeTrash(uid(r), id)));
    }

    // ---------------- 通知 ----------------
    @GetMapping("/notifications")
    public ResponseEntity<?> listNotifications(HttpServletRequest r, @RequestParam(defaultValue = "false") boolean unread) {
        return ResponseEntity.ok(ApiResponse.ok(service.listNotifications(uid(r), unread)));
    }
    @PostMapping("/notifications/read-all")
    public ResponseEntity<?> readAllNotifications(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        List<String> ids = body == null ? null : (List<String>) body.get("ids");
        return ResponseEntity.ok(ApiResponse.ok(service.readAllNotifications(uid(r), ids)));
    }

    // ---------------- 评论 ----------------
    @GetMapping("/comments")
    public ResponseEntity<?> listComments(HttpServletRequest r, @RequestParam(required = false) String doc) {
        return ResponseEntity.ok(ApiResponse.ok(service.listComments(uid(r), doc)));
    }
    @PostMapping("/comments")
    public ResponseEntity<?> addComment(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.addComment(uid(r), str(body.get("docId")), str(body.get("text")), uname(r))));
    }
    @PostMapping("/comments/{id}/reply")
    public ResponseEntity<?> replyComment(HttpServletRequest r, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.replyComment(uid(r), id, str(body.get("text")), uname(r))));
    }
    @DeleteMapping("/comments/{id}")
    public ResponseEntity<?> deleteComment(HttpServletRequest r, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.deleteComment(uid(r), id)));
    }

    // ---------------- 提及 ----------------
    @GetMapping("/mentions")
    public ResponseEntity<?> listMentions(HttpServletRequest r, @RequestParam(defaultValue = "false") boolean unread) {
        return ResponseEntity.ok(ApiResponse.ok(service.listMentions(uid(r), unread)));
    }
    @PostMapping("/mentions/read-all")
    public ResponseEntity<?> readAllMentions(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        List<String> ids = body == null ? null : (List<String>) body.get("ids");
        return ResponseEntity.ok(ApiResponse.ok(service.readAllMentions(uid(r), ids)));
    }
    @PostMapping("/mention")
    public ResponseEntity<?> addMention(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.addMention(uid(r), body, uname(r))));
    }

    // ---------------- 权限申请 / 审批 ----------------
    @GetMapping("/access-requests")
    public ResponseEntity<?> listAccess(HttpServletRequest r, @RequestParam(defaultValue = "false") boolean inbox) {
        return ResponseEntity.ok(ApiResponse.ok(service.listAccessRequests(uid(r), inbox)));
    }
    @PostMapping("/access-request")
    public ResponseEntity<?> addAccess(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.addAccessRequest(uid(r), body, uname(r))));
    }
    @PostMapping("/access-requests/{id}")
    public ResponseEntity<?> decideAccess(HttpServletRequest r, @PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve")) || "true".equals(String.valueOf(body.get("approve")));
        return ResponseEntity.ok(ApiResponse.ok(service.decideAccessRequest(uid(r), id, approve, uname(r))));
    }
    @PatchMapping("/access-requests/{id}")
    public ResponseEntity<?> decideAccessPatch(HttpServletRequest r, @PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve")) || "true".equals(String.valueOf(body.get("approve")));
        return ResponseEntity.ok(ApiResponse.ok(service.decideAccessRequest(uid(r), id, approve, uname(r))));
    }

    // ---------------- 在线 / 心跳 ----------------
    @GetMapping("/presence")
    public ResponseEntity<?> presence(HttpServletRequest r) {
        return ResponseEntity.ok(ApiResponse.ok(service.presence()));
    }
    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(HttpServletRequest r, @RequestBody(required = false) Map<String, Object> body) {
        String docId = body == null ? null : str(body.get("docId"));
        return ResponseEntity.ok(ApiResponse.ok(service.heartbeat(uid(r), uname(r), docId)));
    }

    // ---------------- 统计 / 上传 ----------------
    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest r) {
        return ResponseEntity.ok(ApiResponse.ok(service.stats(uid(r))));
    }

    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.upload(uid(r), str(body.get("dataUrl")), false)));
    }

    @PostMapping(value = "/upload/audio", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadAudio(HttpServletRequest r, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.upload(uid(r), str(body.get("dataUrl")), true)));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(ApiResponse.ok("ok"));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
