package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.AiChatMessageView;
import com.example.website.dto.AiConversationCreateRequest;
import com.example.website.dto.AiConversationReplyView;
import com.example.website.dto.AiConversationSendRequest;
import com.example.website.dto.AiConversationView;
import com.example.website.dto.AiModelView;
import com.example.website.dto.AiTtsRequest;
import com.example.website.dto.AiVoiceView;
import com.example.website.dto.MessageAudioRegenerateRequest;
import com.example.website.dto.PageView;
import com.example.website.service.AiConversationService;
import com.example.website.service.UserScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/user/ai")
public class AiConversationController {

    private static final long STREAM_TIMEOUT_MS = 5L * 60 * 1000;

    private final AiConversationService aiConversationService;
    private final UserScopeService userScopeService;
    private final TaskExecutor aiStreamExecutor;

    public AiConversationController(AiConversationService aiConversationService,
                                    UserScopeService userScopeService,
                                    @Qualifier("aiStreamExecutor") TaskExecutor aiStreamExecutor) {
        this.aiConversationService = aiConversationService;
        this.userScopeService = userScopeService;
        this.aiStreamExecutor = aiStreamExecutor;
    }

    @GetMapping("/models")
    public ApiResponse<List<AiModelView>> models(HttpServletRequest request) {
        userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(aiConversationService.listModels());
    }

    @GetMapping("/voices")
    public ApiResponse<List<AiVoiceView>> voices(HttpServletRequest request) {
        userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(aiConversationService.listVoices());
    }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> tts(HttpServletRequest request,
                                      @Valid @RequestBody AiTtsRequest req) {
        userScopeService.requireAuthenticatedUserId(request);
        AiConversationService.MessageAudioResponse audio = aiConversationService.synthesizeText(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, audio.getMimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + audio.getFilename() + "\"")
                .body(audio.getData());
    }

    @PostMapping("/conversations")
    public ApiResponse<AiConversationView> createConversation(HttpServletRequest request,
                                                              @Valid @RequestBody(required = false) AiConversationCreateRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(aiConversationService.createConversation(
                userId,
                req == null ? new AiConversationCreateRequest() : req
        ));
    }

    @GetMapping("/conversations")
    public ApiResponse<PageView<AiConversationView>> conversations(HttpServletRequest request,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(aiConversationService.listConversations(
                userScopeService.requireAuthenticatedUserId(request),
                page,
                size
        ));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<AiConversationView> conversation(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(aiConversationService.getConversation(
                userScopeService.requireAuthenticatedUserId(request),
                id
        ));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(HttpServletRequest request, @PathVariable Long id) {
        aiConversationService.deleteConversation(
                userScopeService.requireAuthenticatedUserId(request),
                id
        );
        return ApiResponse.ok();
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<PageView<AiChatMessageView>> messages(HttpServletRequest request,
                                                             @PathVariable Long id,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(aiConversationService.listMessages(
                userScopeService.requireAuthenticatedUserId(request),
                id,
                page,
                size
        ));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AiConversationReplyView> sendMessage(HttpServletRequest request,
                                                            @PathVariable Long id,
                                                            @Valid @RequestBody AiConversationSendRequest req) {
        return ApiResponse.ok(aiConversationService.sendMessage(
                userScopeService.requireAuthenticatedUserId(request),
                id,
                req
        ));
    }

    @PostMapping(value = "/conversations/{id}/messages",
            params = "stream=true",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(HttpServletRequest request,
                                    @PathVariable Long id,
                                    @Valid @RequestBody AiConversationSendRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        aiConversationService.streamMessage(userId, id, req, emitter, aiStreamExecutor);
        return emitter;
    }

    @GetMapping("/messages/{id}/audio")
    public ResponseEntity<byte[]> messageAudio(HttpServletRequest request, @PathVariable Long id) {
        AiConversationService.MessageAudioResponse audio = aiConversationService.getMessageAudio(
                userScopeService.requireAuthenticatedUserId(request),
                id
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, audio.getMimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + audio.getFilename() + "\"")
                .body(audio.getData());
    }

    @PostMapping("/messages/{id}/audio")
    public ApiResponse<AiChatMessageView> regenerateMessageAudio(HttpServletRequest request,
                                                                 @PathVariable Long id,
                                                                 @Valid @RequestBody(required = false) MessageAudioRegenerateRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(aiConversationService.regenerateMessageAudio(
                userId,
                id,
                req == null ? new MessageAudioRegenerateRequest() : req
        ));
    }
}
