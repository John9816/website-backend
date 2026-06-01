package com.example.website.service;

import com.example.website.common.BusinessException;
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
import com.example.website.entity.AiChatMessage;
import com.example.website.entity.AiConversation;
import com.example.website.repository.AiChatMessageRepository;
import com.example.website.repository.AiConversationRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationService {

    public static final String CFG_BASE_URL = "ai.chat.baseUrl";
    public static final String CFG_API_KEY = "ai.chat.apiKey";
    public static final String CFG_DEFAULT_MODEL = "ai.chat.defaultModel";
    public static final String CFG_DEFAULT_AUDIO_MODEL = "ai.chat.defaultAudioModel";
    public static final String CFG_MODELS = "ai.chat.models";
    public static final String CFG_VOICES = "ai.chat.voices";

    private static final int MAX_PAGE_SIZE = 100;
    private static final int HISTORY_WINDOW_SIZE = 20;
    private static final String DEFAULT_TITLE = "\u65B0\u5BF9\u8BDD";
    private static final String AUDIO_ONLY_TITLE = "\u8BED\u97F3\u6D88\u606F";
    private static final String AUDIO_ONLY_PLACEHOLDER = "[Audio message]";
    private static final int TITLE_PREVIEW_LENGTH = 40;
    private static final int MESSAGE_PREVIEW_LENGTH = 160;
    private static final String DEFAULT_AUDIO_FORMAT = "wav";
    private static final String DEFAULT_VOICES = "alloy|Alloy,nova|Nova,echo|Echo,fable|Fable,onyx|Onyx,shimmer|Shimmer";
    private static final List<String> FALLBACK_MODELS = Arrays.asList(
            "mimo-v2.5-pro",
            "mimo-v2.5",
            "mimo-v2.5-tts",
            "mimo-v2.5-tts-voicedesign",
            "mimo-v2.5-tts-voiceclone",
            "mimo-v2-pro",
            "mimo-v2-flash",
            "mimo-v2-omni",
            "mimo-v2-tts"
    );

    private final AiConversationRepository conversationRepository;
    private final AiChatMessageRepository messageRepository;
    private final SysConfigService configService;
    private final AiChatUpstreamClient upstreamClient;
    private final TransactionTemplate transactionTemplate;

    public List<AiModelView> listModels() {
        String defaultModel = getDefaultModel();
        return getSupportedModels().stream()
                .map(model -> new AiModelView(model, model.equals(defaultModel), describeCapabilities(model)))
                .collect(Collectors.toList());
    }

    public PageView<AiConversationView> listConversations(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(safePage(page), safeSize(size));
        Page<AiConversation> result = conversationRepository.findByUserIdOrderByLastMessageAtDescIdDesc(userId, pageable);
        return PageView.from(result, AiConversationView::from);
    }

    public AiConversationView getConversation(Long userId, Long conversationId) {
        return AiConversationView.from(requireConversation(userId, conversationId));
    }

    public PageView<AiChatMessageView> listMessages(Long userId, Long conversationId, int page, int size) {
        requireConversation(userId, conversationId);
        Pageable pageable = PageRequest.of(safePage(page), safeSize(size));
        Page<AiChatMessageRepository.AiChatMessageSummary> result =
                messageRepository.findSummariesByConversationId(conversationId, pageable);
        return PageView.from(result, AiChatMessageView::from);
    }

    public MessageAudioResponse getMessageAudio(Long userId, Long messageId) {
        AiChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(404, "Message not found"));
        requireConversation(userId, message.getConversationId());
        String audioData = trimToNull(message.getAudioData());
        if (audioData == null) {
            throw new BusinessException(404, "Message audio not found");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(audioData);
            return new MessageAudioResponse(
                    bytes,
                    trimToNull(message.getAudioMimeType()) == null ? "application/octet-stream" : message.getAudioMimeType(),
                    "message-" + messageId + fileExtension(message.getAudioMimeType())
            );
        } catch (IllegalArgumentException e) {
            throw new BusinessException(500, "Stored audio data is invalid");
        }
    }

    public AiConversationView createConversation(Long userId, AiConversationCreateRequest req) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AiConversation conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setTitle(hasText(req.getTitle()) ? req.getTitle().trim() : DEFAULT_TITLE);
            conversation.setModel(resolveRequestedChatModel(req.getModel(), null));
            conversation.setLastMessageAt(now);
            return AiConversationView.from(conversationRepository.save(conversation));
        });
    }

    public AiConversationReplyView sendMessage(Long userId, Long conversationId, AiConversationSendRequest req) {
        AiConversation current = requireConversation(userId, conversationId);
        String requestedContent = trimToNull(req.getContent());
        InputAudioPayload inputAudio = parseInputAudio(req.getInputAudioData());
        String chatModel = resolveRequestedChatModel(req.getModel(), current.getModel());

        if (inputAudio != null && !supportsAudioInput(chatModel)) {
            throw new BusinessException(400, "Model does not support audio input: " + chatModel);
        }

        List<AiChatMessage> existingMessages = loadRecentHistory(conversationId);
        List<AiChatUpstreamClient.ChatMessage> upstreamMessages = buildHistoryMessages(
                existingMessages,
                supportsAudioInput(chatModel)
        );
        upstreamMessages.add(new AiChatUpstreamClient.ChatMessage(
                AiChatMessage.ROLE_USER,
                buildUserContent(requestedContent, inputAudio, supportsAudioInput(chatModel))
        ));

        AiChatUpstreamClient.ChatCompletionResult chatResult = upstreamClient.complete(
                configService.getValueOrThrow(CFG_BASE_URL),
                configService.getValue(CFG_API_KEY).orElse(""),
                chatModel,
                upstreamMessages
        );

        String assistantText = trimToNull(chatResult.getContent());
        if (assistantText == null) {
            throw new BusinessException(502, "Upstream returned no assistant content");
        }

        String ttsModel = null;
        AiChatUpstreamClient.ChatAudioResult responseAudio = null;
        if (req.isResponseAudio() || hasText(req.getTtsModel())) {
            ttsModel = resolveRequestedAudioModel(req.getTtsModel());
            responseAudio = synthesizeAssistantAudio(assistantText, ttsModel, req);
        }

        boolean firstTurn = messageRepository.countByConversationId(conversationId) == 0;
        PersistedTurn persisted = persistSuccessfulTurn(
                userId,
                conversationId,
                requestedContent,
                chatModel,
                inputAudio,
                assistantText,
                chatResult,
                ttsModel,
                responseAudio,
                firstTurn
        );

        return new AiConversationReplyView(
                AiConversationView.from(persisted.getConversation()),
                AiChatMessageView.from(persisted.getUserMessage()),
                AiChatMessageView.from(persisted.getAssistantMessage())
        );
    }

    public void streamMessage(Long userId,
                              Long conversationId,
                              AiConversationSendRequest req,
                              SseEmitter emitter,
                              Executor executor) {
        AiConversation current = requireConversation(userId, conversationId);
        String requestedContent = trimToNull(req.getContent());
        InputAudioPayload inputAudio = parseInputAudio(req.getInputAudioData());
        String chatModel = resolveRequestedChatModel(req.getModel(), current.getModel());
        if (inputAudio != null && !supportsAudioInput(chatModel)) {
            throw new BusinessException(400, "Model does not support audio input: " + chatModel);
        }

        boolean wantsAudio = req.isResponseAudio() || hasText(req.getTtsModel());
        String resolvedTtsModel = wantsAudio ? resolveRequestedAudioModel(req.getTtsModel()) : null;

        List<AiChatMessage> existingMessages = loadRecentHistory(conversationId);
        List<AiChatUpstreamClient.ChatMessage> upstreamMessages = buildHistoryMessages(
                existingMessages,
                supportsAudioInput(chatModel)
        );
        upstreamMessages.add(new AiChatUpstreamClient.ChatMessage(
                AiChatMessage.ROLE_USER,
                buildUserContent(requestedContent, inputAudio, supportsAudioInput(chatModel))
        ));

        boolean firstTurn = messageRepository.countByConversationId(conversationId) == 0;
        String baseUrl = configService.getValueOrThrow(CFG_BASE_URL);
        String apiKey = configService.getValue(CFG_API_KEY).orElse("");

        AtomicReference<okhttp3.Call> callRef = new AtomicReference<>();
        Runnable cancelCall = () -> {
            okhttp3.Call call = callRef.get();
            if (call != null && !call.isCanceled()) {
                call.cancel();
            }
        };
        emitter.onTimeout(cancelCall);
        emitter.onCompletion(cancelCall);
        emitter.onError(t -> cancelCall.run());

        executor.execute(() -> {
            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("conversationId", conversationId);
                meta.put("model", chatModel);
                emitter.send(SseEmitter.event().name("meta").data(meta));
            } catch (IOException e) {
                emitter.completeWithError(e);
                return;
            }

            StreamAccumulator acc = new StreamAccumulator();
            AiChatUpstreamClient.StreamHandler handler = new AiChatUpstreamClient.StreamHandler() {
                @Override
                public void onDelta(String contentDelta) {
                    if (acc.failure != null || acc.clientGone) {
                        return;
                    }
                    try {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("content", contentDelta);
                        emitter.send(SseEmitter.event().name("delta").data(data));
                    } catch (IOException e) {
                        acc.clientGone = true;
                        cancelCall.run();
                    }
                }

                @Override
                public void onUsage(Integer prompt, Integer completion, Integer total) {
                    acc.promptTokens = prompt;
                    acc.completionTokens = completion;
                    acc.totalTokens = total;
                }

                @Override
                public void onFinish(String finishReason, String fullContent, String resolvedModel) {
                    acc.finishReason = finishReason;
                    acc.fullContent = fullContent;
                    acc.resolvedModel = resolvedModel;
                }

                @Override
                public void onError(BusinessException ex) {
                    acc.failure = ex;
                }
            };

            okhttp3.Call call = upstreamClient.streamComplete(baseUrl, apiKey, chatModel, upstreamMessages, handler);
            callRef.set(call);

            if (acc.clientGone) {
                emitter.complete();
                return;
            }
            if (acc.failure != null) {
                sendErrorEvent(emitter, acc.failure);
                return;
            }

            String assistantText = trimToNull(acc.fullContent);
            if (assistantText == null) {
                sendErrorEvent(emitter, new BusinessException(502, "Upstream returned no assistant content"));
                return;
            }

            try {
                AiChatUpstreamClient.ChatCompletionResult chatResult = new AiChatUpstreamClient.ChatCompletionResult(
                        acc.resolvedModel == null ? chatModel : acc.resolvedModel,
                        assistantText,
                        acc.finishReason,
                        acc.promptTokens,
                        acc.completionTokens,
                        acc.totalTokens,
                        null
                );

                String ttsModel = null;
                AiChatUpstreamClient.ChatAudioResult responseAudio = null;
                BusinessException ttsFailure = null;
                if (wantsAudio) {
                    ttsModel = resolvedTtsModel;
                    try {
                        responseAudio = synthesizeAssistantAudio(assistantText, ttsModel, req);
                    } catch (BusinessException ex) {
                        ttsFailure = ex;
                    } catch (RuntimeException ex) {
                        ttsFailure = new BusinessException(502, "Audio synthesis failed: " + ex.getMessage());
                    }
                }

                PersistedTurn persisted = persistSuccessfulTurn(
                        userId,
                        conversationId,
                        requestedContent,
                        chatModel,
                        inputAudio,
                        assistantText,
                        chatResult,
                        responseAudio == null ? null : ttsModel,
                        responseAudio,
                        firstTurn
                );
                AiConversationReplyView reply = new AiConversationReplyView(
                        AiConversationView.from(persisted.getConversation()),
                        AiChatMessageView.from(persisted.getUserMessage()),
                        AiChatMessageView.from(persisted.getAssistantMessage())
                );

                if (responseAudio != null) {
                    AiChatMessageView assistantView = reply.getAssistantMessage();
                    Map<String, Object> audioData = new LinkedHashMap<>();
                    audioData.put("messageId", assistantView.getId());
                    audioData.put("audioUrl", assistantView.getAudioUrl());
                    audioData.put("audioMimeType", assistantView.getAudioMimeType());
                    audioData.put("audioModel", assistantView.getAudioModel());
                    emitter.send(SseEmitter.event().name("audio").data(audioData));
                } else if (ttsFailure != null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("code", ttsFailure.getCode());
                    err.put("message", ttsFailure.getMessage());
                    emitter.send(SseEmitter.event().name("audio_error").data(err));
                }

                emitter.send(SseEmitter.event().name("done").data(reply));
                emitter.complete();
            } catch (BusinessException e) {
                sendErrorEvent(emitter, e);
            } catch (Exception e) {
                log.error("Failed to persist streamed AI turn", e);
                sendErrorEvent(emitter, new BusinessException(500, "Failed to persist streamed turn: " + e.getMessage()));
            }
        });
    }

    private void sendErrorEvent(SseEmitter emitter, BusinessException ex) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ex.getCode());
            err.put("message", ex.getMessage());
            emitter.send(SseEmitter.event().name("error").data(err));
        } catch (IOException ignore) {
            // client likely gone
        } finally {
            emitter.complete();
        }
    }

    private static class StreamAccumulator {
        String fullContent;
        String resolvedModel;
        String finishReason;
        Integer promptTokens;
        Integer completionTokens;
        Integer totalTokens;
        BusinessException failure;
        volatile boolean clientGone;
    }

    private AiChatUpstreamClient.ChatAudioResult synthesizeAssistantAudio(String assistantText,
                                                                          String ttsModel,
                                                                          AiConversationSendRequest req) {
        return synthesizeAudio(assistantText, ttsModel, req.getTtsVoice(), req.getTtsFormat(), req.getTtsPrompt());
    }

    private AiChatUpstreamClient.ChatAudioResult synthesizeAudio(String text,
                                                                 String ttsModel,
                                                                 String ttsVoice,
                                                                 String ttsFormat,
                                                                 String ttsPrompt) {
        String input = buildSpeechInput(text, ttsPrompt);
        return upstreamClient.createSpeech(
                configService.getValueOrThrow(CFG_BASE_URL),
                configService.getValue(CFG_API_KEY).orElse(""),
                ttsModel,
                input,
                trimToNull(ttsVoice),
                normalizeAudioFormat(ttsFormat),
                null
        );
    }

    public List<AiVoiceView> listVoices() {
        return getConfiguredVoices();
    }

    public MessageAudioResponse synthesizeText(AiTtsRequest req) {
        String text = trimToNull(req.getText());
        if (text == null) {
            throw new BusinessException(400, "text is required");
        }
        String ttsModel = resolveRequestedAudioModel(req.getTtsModel());
        AiChatUpstreamClient.ChatAudioResult audio = synthesizeAudio(
                text, ttsModel, req.getTtsVoice(), req.getTtsFormat(), req.getTtsPrompt()
        );
        try {
            byte[] bytes = Base64.getDecoder().decode(audio.getData());
            String mimeType = trimToNull(audio.getMimeType()) == null
                    ? "application/octet-stream" : audio.getMimeType();
            String filename = "tts" + fileExtension(mimeType);
            return new MessageAudioResponse(bytes, mimeType, filename);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(502, "Upstream audio data is invalid");
        }
    }

    public AiChatMessageView regenerateMessageAudio(Long userId,
                                                    Long messageId,
                                                    MessageAudioRegenerateRequest req) {
        AiChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(404, "Message not found"));
        requireConversation(userId, message.getConversationId());
        if (!AiChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BusinessException(400, "Only assistant messages support audio regeneration");
        }
        String text = trimToNull(message.getContent());
        if (text == null) {
            throw new BusinessException(400, "Message has no text content to synthesize");
        }
        MessageAudioRegenerateRequest payload = req == null ? new MessageAudioRegenerateRequest() : req;
        String ttsModel = resolveRequestedAudioModel(payload.getTtsModel());
        AiChatUpstreamClient.ChatAudioResult audio = synthesizeAudio(
                text, ttsModel, payload.getTtsVoice(), payload.getTtsFormat(), payload.getTtsPrompt()
        );
        AiChatMessage saved = transactionTemplate.execute(status -> {
            AiChatMessage current = messageRepository.findById(messageId)
                    .orElseThrow(() -> new BusinessException(404, "Message not found"));
            requireConversation(userId, current.getConversationId());
            current.setAudioModel(ttsModel);
            current.setAudioData(audio.getData());
            current.setAudioMimeType(audio.getMimeType());
            current.setAudioExternalId(audio.getId());
            return messageRepository.save(current);
        });
        return AiChatMessageView.from(saved);
    }

    private List<AiVoiceView> getConfiguredVoices() {
        String configured = configService.getValue(CFG_VOICES).orElse(DEFAULT_VOICES);
        List<AiVoiceView> voices = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : configured.split("[,\\r\\n]+")) {
            String trimmed = trimToNull(entry);
            if (trimmed == null) {
                continue;
            }
            int sep = trimmed.indexOf('|');
            String id = sep < 0 ? trimmed : trimToNull(trimmed.substring(0, sep));
            String label = sep < 0 ? trimmed : trimToNull(trimmed.substring(sep + 1));
            if (id == null) {
                continue;
            }
            if (label == null) {
                label = id;
            }
            if (seen.add(id)) {
                voices.add(new AiVoiceView(id, label));
            }
        }
        if (voices.isEmpty()) {
            throw new BusinessException(500, "No AI voices configured");
        }
        return voices;
    }

    private PersistedTurn persistSuccessfulTurn(Long userId,
                                                Long conversationId,
                                                String userContent,
                                                String model,
                                                InputAudioPayload inputAudio,
                                                String assistantText,
                                                AiChatUpstreamClient.ChatCompletionResult chatResult,
                                                String ttsModel,
                                                AiChatUpstreamClient.ChatAudioResult responseAudio,
                                                boolean firstTurn) {
        return transactionTemplate.execute(status -> {
            AiConversation conversation = requireConversation(userId, conversationId);
            LocalDateTime now = LocalDateTime.now();

            AiChatMessage userMessage = new AiChatMessage();
            userMessage.setConversationId(conversationId);
            userMessage.setRole(AiChatMessage.ROLE_USER);
            userMessage.setContent(userContent == null ? "" : userContent);
            userMessage.setModel(model);
            applyInputAudio(userMessage, inputAudio);

            AiChatMessage assistantMessage = new AiChatMessage();
            assistantMessage.setConversationId(conversationId);
            assistantMessage.setRole(AiChatMessage.ROLE_ASSISTANT);
            assistantMessage.setContent(assistantText);
            assistantMessage.setModel(chatResult.getModel());
            assistantMessage.setFinishReason(chatResult.getFinishReason());
            assistantMessage.setPromptTokens(chatResult.getPromptTokens());
            assistantMessage.setCompletionTokens(chatResult.getCompletionTokens());
            assistantMessage.setTotalTokens(chatResult.getTotalTokens());
            applyAssistantAudio(assistantMessage, ttsModel, responseAudio);

            if (firstTurn && isDefaultTitle(conversation.getTitle())) {
                conversation.setTitle(buildInitialTitle(userContent, inputAudio));
            }
            conversation.setModel(model);
            conversation.setLastMessagePreview(buildPreview(assistantText, MESSAGE_PREVIEW_LENGTH));
            conversation.setLastMessageAt(now);

            AiChatMessage savedUserMessage = messageRepository.save(userMessage);
            AiChatMessage savedAssistantMessage = messageRepository.save(assistantMessage);
            AiConversation savedConversation = conversationRepository.save(conversation);
            return new PersistedTurn(savedConversation, savedUserMessage, savedAssistantMessage);
        });
    }

    private List<AiChatUpstreamClient.ChatMessage> buildHistoryMessages(List<AiChatMessage> existingMessages,
                                                                        boolean includeAudioInputs) {
        List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
        for (AiChatMessage message : existingMessages) {
            boolean attachAudio = includeAudioInputs && AiChatMessage.ROLE_USER.equals(message.getRole()) && hasStoredAudio(message);
            messages.add(new AiChatUpstreamClient.ChatMessage(
                    message.getRole(),
                    buildStoredMessageContent(message, attachAudio)
            ));
        }
        return messages;
    }

    private Object buildStoredMessageContent(AiChatMessage message, boolean attachAudio) {
        String text = trimToNull(message.getContent());
        if (!attachAudio) {
            return text == null ? (hasStoredAudio(message) ? AUDIO_ONLY_PLACEHOLDER : "") : text;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null) {
            parts.add(textPart(text));
        }
        parts.add(inputAudioPart(buildStoredAudioInputData(message)));
        return parts;
    }

    private Object buildUserContent(String text, InputAudioPayload inputAudio, boolean supportsAudioInput) {
        if (inputAudio == null) {
            return text == null ? "" : text;
        }
        if (!supportsAudioInput) {
            return text == null ? AUDIO_ONLY_PLACEHOLDER : text;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null) {
            parts.add(textPart(text));
        }
        parts.add(inputAudioPart(inputAudio.getUpstreamData()));
        return parts;
    }

    private Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        return part;
    }

    private Map<String, Object> inputAudioPart(String data) {
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", data);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "input_audio");
        part.put("input_audio", inputAudio);
        return part;
    }

    private InputAudioPayload parseInputAudio(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return new InputAudioPayload(value, null, null, value);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:audio/")) {
            int comma = value.indexOf(',');
            int base64Marker = lower.indexOf(";base64,");
            if (comma < 0 || base64Marker < 0 || base64Marker > comma) {
                throw new BusinessException(400, "inputAudioData must be a valid data:audio/...;base64,... value");
            }
            String mimeType = value.substring("data:".length(), base64Marker);
            String data = value.substring(comma + 1);
            if (!hasText(data)) {
                throw new BusinessException(400, "inputAudioData must contain base64 audio data");
            }
            return new InputAudioPayload(null, data, mimeType, value);
        }
        throw new BusinessException(400, "inputAudioData must be a public URL or data:audio/...;base64,...");
    }

    private void applyInputAudio(AiChatMessage message, InputAudioPayload inputAudio) {
        if (inputAudio == null) {
            return;
        }
        message.setAudioSourceUrl(inputAudio.getSourceUrl());
        message.setAudioData(inputAudio.getBase64Data());
        message.setAudioMimeType(inputAudio.getMimeType());
    }

    private void applyAssistantAudio(AiChatMessage message,
                                     String audioModel,
                                     AiChatUpstreamClient.ChatAudioResult responseAudio) {
        if (responseAudio == null) {
            return;
        }
        message.setAudioModel(audioModel);
        message.setAudioData(responseAudio.getData());
        message.setAudioMimeType(responseAudio.getMimeType());
        message.setAudioExternalId(responseAudio.getId());
    }

    private boolean hasStoredAudio(AiChatMessage message) {
        return hasText(message.getAudioSourceUrl()) || hasText(message.getAudioData());
    }

    private String buildStoredAudioInputData(AiChatMessage message) {
        if (hasText(message.getAudioSourceUrl())) {
            return message.getAudioSourceUrl().trim();
        }
        if (hasText(message.getAudioData()) && hasText(message.getAudioMimeType())) {
            return "data:" + message.getAudioMimeType().trim() + ";base64," + message.getAudioData().trim();
        }
        throw new BusinessException(500, "Stored audio message is incomplete");
    }

    private String buildInitialTitle(String userContent, InputAudioPayload inputAudio) {
        if (hasText(userContent)) {
            return buildPreview(userContent, TITLE_PREVIEW_LENGTH);
        }
        if (inputAudio != null) {
            return AUDIO_ONLY_TITLE;
        }
        return DEFAULT_TITLE;
    }

    private AiConversation requireConversation(Long userId, Long conversationId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(404, "Conversation not found"));
    }

    private String resolveRequestedChatModel(String requestedModel, String fallbackModel) {
        String requested = trimToNull(requestedModel);
        if (requested != null) {
            if (!getSupportedModels().contains(requested)) {
                throw new BusinessException(400, "Unsupported model: " + requested);
            }
            if (!supportsTextChat(requested)) {
                throw new BusinessException(400, "Model does not support text dialogue: " + requested);
            }
            return requested;
        }

        String fallback = trimToNull(fallbackModel);
        if (fallback != null && getSupportedModels().contains(fallback) && supportsTextChat(fallback)) {
            return fallback;
        }

        String configured = resolveConfiguredModel();
        if (!supportsTextChat(configured)) {
            throw new BusinessException(500, "Configured default model does not support text dialogue: " + configured);
        }
        return configured;
    }

    private String resolveRequestedAudioModel(String requestedModel) {
        String model = trimToNull(requestedModel);
        if (model == null) {
            String configured = getDefaultAudioModel();
            if (!getSupportedModels().contains(configured)) {
                throw new BusinessException(500, "Configured default audio model is not allowed: " + configured);
            }
            if (!supportsAudioOutput(configured)) {
                throw new BusinessException(500, "Configured default audio model does not support audio output: " + configured);
            }
            model = configured;
        }
        if (!getSupportedModels().contains(model)) {
            throw new BusinessException(400, "Unsupported model: " + model);
        }
        if (!supportsAudioOutput(model)) {
            throw new BusinessException(400, "Model does not support audio output: " + model);
        }
        return model;
    }

    private String resolveConfiguredModel() {
        List<String> supportedModels = getSupportedModels();
        String model = getDefaultModel();
        if (!supportedModels.contains(model)) {
            throw new BusinessException(500, "Configured default model is not allowed: " + model);
        }
        return model;
    }

    private String resolveRequestedModel(String requestedModel, String fallbackModel) {
        List<String> supportedModels = getSupportedModels();
        String requested = trimToNull(requestedModel);
        if (requested != null) {
            if (!supportedModels.contains(requested)) {
                throw new BusinessException(400, "Unsupported model: " + requested);
            }
            return requested;
        }

        String fallback = trimToNull(fallbackModel);
        if (fallback != null && supportedModels.contains(fallback)) {
            return fallback;
        }
        return resolveConfiguredModel();
    }

    private List<String> getSupportedModels() {
        String configured = configService.getValue(CFG_MODELS).orElse(String.join(",", FALLBACK_MODELS));
        List<String> models = Arrays.stream(configured.split("[,\\r\\n]+"))
                .map(this::trimToNull)
                .filter(this::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (models.isEmpty()) {
            throw new BusinessException(500, "No AI models configured");
        }
        return models;
    }

    private String getDefaultModel() {
        String configured = trimToNull(configService.getValue(CFG_DEFAULT_MODEL).orElse(null));
        if (configured != null) {
            return configured;
        }
        return FALLBACK_MODELS.get(0);
    }

    private String getDefaultAudioModel() {
        String configured = trimToNull(configService.getValue(CFG_DEFAULT_AUDIO_MODEL).orElse(null));
        if (configured != null) {
            return configured;
        }
        for (String model : getSupportedModels()) {
            if (supportsAudioOutput(model)) {
                return model;
            }
        }
        throw new BusinessException(500, "No audio output model configured");
    }

    private List<String> describeCapabilities(String model) {
        if (!hasText(model)) {
            return Collections.singletonList("text_chat");
        }
        Set<String> capabilities = new LinkedHashSet<>();
        if (supportsTextChat(model)) {
            capabilities.add("text_chat");
        }
        if (supportsAudioInput(model)) {
            capabilities.add("audio_input");
        }
        if (supportsAudioOutput(model)) {
            capabilities.add("audio_output");
        }
        if (supportsVoiceCustomization(model)) {
            capabilities.add("voice_customization");
        }
        return new ArrayList<>(capabilities);
    }

    private boolean supportsTextChat(String model) {
        String normalized = normalizeModel(model);
        return normalized != null && !normalized.contains("tts");
    }

    private boolean supportsAudioInput(String model) {
        String normalized = normalizeModel(model);
        return "mimo-v2-omni".equals(normalized) || "mimo-v2.5".equals(normalized);
    }

    private boolean supportsAudioOutput(String model) {
        String normalized = normalizeModel(model);
        return normalized != null && normalized.contains("tts");
    }

    private boolean supportsVoiceCustomization(String model) {
        String normalized = normalizeModel(model);
        return normalized != null
                && (normalized.contains("voiceclone") || normalized.contains("voicedesign"));
    }

    private String normalizeModel(String model) {
        return model == null ? null : model.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeAudioFormat(String audioFormat) {
        String format = trimToNull(audioFormat);
        return format == null ? DEFAULT_AUDIO_FORMAT : format.toLowerCase(Locale.ROOT);
    }

    private String buildSpeechInput(String text, String ttsPrompt) {
        String prompt = trimToNull(ttsPrompt);
        if (prompt == null) {
            return text;
        }
        return prompt + "\n\n" + text;
    }

    private String fileExtension(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        if ("audio/wav".equalsIgnoreCase(mimeType)) {
            return ".wav";
        }
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return ".mp3";
        }
        if ("audio/flac".equalsIgnoreCase(mimeType)) {
            return ".flac";
        }
        if ("audio/ogg".equalsIgnoreCase(mimeType)) {
            return ".ogg";
        }
        return ".bin";
    }

    private int safePage(int page) {
        return Math.max(0, page);
    }

    private int safeSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    /**
     * Loads the most recent N messages of a conversation for inclusion in the
     * upstream chat history. Older messages are dropped to bound payload size
     * and avoid pulling LOB columns (audioData) for every prior turn.
     */
    private List<AiChatMessage> loadRecentHistory(Long conversationId) {
        List<AiChatMessage> recent = messageRepository.findRecentByConversationId(
                conversationId,
                PageRequest.of(0, HISTORY_WINDOW_SIZE)
        );
        java.util.Collections.reverse(recent);
        return recent;
    }

    private boolean isDefaultTitle(String title) {
        return !hasText(title) || DEFAULT_TITLE.equals(title.trim());
    }

    private String buildPreview(String text, int maxLength) {
        String compact = trimToNull(text == null ? null : text.replaceAll("\\s+", " "));
        if (compact == null) {
            return DEFAULT_TITLE;
        }
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasText(String text) {
        return trimToNull(text) != null;
    }

    @Getter
    @AllArgsConstructor
    private static class PersistedTurn {
        private final AiConversation conversation;
        private final AiChatMessage userMessage;
        private final AiChatMessage assistantMessage;
    }

    @Getter
    @AllArgsConstructor
    private static class InputAudioPayload {
        private final String sourceUrl;
        private final String base64Data;
        private final String mimeType;
        private final String upstreamData;
    }

    @Getter
    @AllArgsConstructor
    public static class MessageAudioResponse {
        private final byte[] data;
        private final String mimeType;
        private final String filename;
    }
}
