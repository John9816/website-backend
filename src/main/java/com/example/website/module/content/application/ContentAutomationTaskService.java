package com.example.website.module.content.application;

import com.example.website.common.BusinessException;
import com.example.website.module.content.api.dto.ContentAgentRunRequest;
import com.example.website.module.content.api.dto.ContentAgentRunResult;
import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;
import com.example.website.module.content.api.dto.ContentArticleView;
import com.example.website.module.content.domain.ContentAutomationTask;
import com.example.website.module.content.domain.ContentTemplate;
import com.example.website.module.content.infrastructure.persistence.ContentAutomationTaskRepository;
import com.example.website.module.content.infrastructure.persistence.ContentTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentAutomationTaskService {

    private static final int MAX_BATCH_SIZE = 20;
    private static final Set<String> TASK_TYPES = new HashSet<>(Arrays.asList(
            "GENERATE", "GENERATE_ARTICLE", "BATCH", "PUBLISH", "PUBLISH_ARTICLE"));
    private static final Set<String> SCHEDULE_TYPES = new HashSet<>(Arrays.asList(
            "ONCE", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "CUSTOM"));

    private final ContentAutomationTaskRepository taskRepository;
    private final ContentTemplateRepository templateRepository;
    private final ContentArticleService contentArticleService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> createTask(Long userId, Map<String, Object> request) {
        requireUserId(userId);
        requireRequest(request);

        ContentAutomationTask task = new ContentAutomationTask();
        task.setUserId(userId);
        task.setTaskName(requiredText(request, "taskName", 200));
        task.setTaskType(normalizeTaskType(requiredText(request, "taskType", 40)));
        task.setScheduleType(normalizeScheduleType(requiredText(request, "scheduleType", 40)));
        task.setScheduleExpression(optionalText(request, "scheduleExpression", 200));
        task.setTemplateId(optionalLong(request, "templateId"));
        task.setConfigJson(writeConfig(request.get("config")));
        task.setStatus("PENDING");
        task.setIsActive(booleanValue(request.get("isActive"), true));

        validateTaskConfiguration(task);
        task.setNextRunAt(task.getIsActive()
                ? calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now())
                : null);

        ContentAutomationTask saved = taskRepository.save(task);
        log.info("User {} created content automation task {} ({})", userId, saved.getId(), saved.getTaskType());
        return buildTaskResponse(saved);
    }

    @Transactional
    public Map<String, Object> updateTask(Long userId, Long taskId, Map<String, Object> request) {
        requireRequest(request);
        ContentAutomationTask task = requireOwnedTask(userId, taskId);

        if (request.containsKey("taskName")) {
            task.setTaskName(requiredText(request, "taskName", 200));
        }
        if (request.containsKey("taskType")) {
            task.setTaskType(normalizeTaskType(requiredText(request, "taskType", 40)));
        }
        if (request.containsKey("scheduleType")) {
            task.setScheduleType(normalizeScheduleType(requiredText(request, "scheduleType", 40)));
        }
        if (request.containsKey("scheduleExpression")) {
            task.setScheduleExpression(optionalText(request, "scheduleExpression", 200));
        }
        if (request.containsKey("templateId")) {
            task.setTemplateId(optionalLong(request, "templateId"));
        }
        if (request.containsKey("config")) {
            task.setConfigJson(writeConfig(request.get("config")));
        }
        if (request.containsKey("isActive")) {
            task.setIsActive(booleanValue(request.get("isActive"), false));
        }

        validateTaskConfiguration(task);
        if (!Boolean.TRUE.equals(task.getIsActive())) {
            task.setNextRunAt(null);
        } else if (request.containsKey("scheduleType") || request.containsKey("scheduleExpression")
                || request.containsKey("isActive")) {
            task.setNextRunAt(calculateNextRunTime(
                    task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now()));
            task.setStatus("PENDING");
        }

        ContentAutomationTask saved = taskRepository.save(task);
        log.info("User {} updated content automation task {}", userId, taskId);
        return buildTaskResponse(saved);
    }

    @Transactional
    public void deleteTask(Long userId, Long taskId) {
        ContentAutomationTask task = requireOwnedTask(userId, taskId);
        taskRepository.delete(task);
        log.info("User {} deleted content automation task {}", userId, taskId);
    }

    public Map<String, Object> getTask(Long userId, Long taskId) {
        return buildTaskResponse(requireOwnedTask(userId, taskId));
    }

    public Page<Map<String, Object>> getTasks(Long userId, Boolean onlyActive, Pageable pageable) {
        Page<ContentAutomationTask> tasks = Boolean.TRUE.equals(onlyActive)
                ? taskRepository.findByUserIdAndIsActiveOrderByIdDesc(userId, true, pageable)
                : taskRepository.findByUserIdOrderByIdDesc(userId, pageable);
        return tasks.map(this::buildTaskListItem);
    }

    public Page<Map<String, Object>> searchTasks(Long userId, String keyword, Pageable pageable) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "keyword cannot be blank");
        }
        return taskRepository.searchByKeyword(userId, normalized, pageable).map(this::buildTaskListItem);
    }

    @Transactional
    public Map<String, Object> toggleTaskStatus(Long userId, Long taskId, boolean active) {
        ContentAutomationTask task = requireOwnedTask(userId, taskId);
        task.setIsActive(active);
        task.setStatus(active ? "PENDING" : task.getStatus());
        task.setNextRunAt(active
                ? calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now())
                : null);
        return buildTaskResponse(taskRepository.save(task));
    }

    public Map<String, Object> executeTask(Long userId, Long taskId) {
        ContentAutomationTask owned = requireOwnedTask(userId, taskId);
        LocalDateTime now = LocalDateTime.now();
        if (taskRepository.claimTask(owned.getId(), now, now.minusHours(2)) == 0) {
            throw new BusinessException(409, "Task is already running");
        }
        ContentAutomationTask claimed = taskRepository.findById(owned.getId())
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
        return executeClaimedTask(claimed, true);
    }

    public Map<String, Object> getStatistics(Long userId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTasks", taskRepository.countByUserId(userId));
        stats.put("activeTasks", taskRepository.countByUserIdAndIsActive(userId, true));
        stats.put("pendingTasks", taskRepository.countByUserIdAndStatus(userId, "PENDING"));
        stats.put("runningTasks", taskRepository.countByUserIdAndStatus(userId, "RUNNING"));
        stats.put("failedTasks", taskRepository.countByUserIdAndStatus(userId, "FAILED"));
        return stats;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void executeScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<ContentAutomationTask> due = taskRepository
                .findByIsActiveAndNextRunAtLessThanEqualOrderByNextRunAtAsc(true, now);
        for (ContentAutomationTask candidate : due) {
            if (taskRepository.claimDueTask(candidate.getId(), now, now.minusHours(2)) == 0) {
                continue;
            }
            ContentAutomationTask claimed = taskRepository.findById(candidate.getId()).orElse(null);
            if (claimed != null) {
                executeClaimedTask(claimed, false);
            }
        }
    }

    private Map<String, Object> executeClaimedTask(ContentAutomationTask task, boolean propagateFailure) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            ContentTemplate template = requireActiveOwnedTemplate(task);
            Map<String, Object> config = applyTemplateDefaults(readConfig(task.getConfigJson()), template);
            Map<String, Object> result = executeConfiguredAction(task, config);
            task.setRunCount(safeCount(task.getRunCount()) + 1);
            task.setSuccessCount(safeCount(task.getSuccessCount()) + 1);
            task.setLastRunAt(startedAt);
            task.setRunStartedAt(null);
            task.setStatus("SUCCESS");
            task.setLastError(null);
            if ("ONCE".equals(task.getScheduleType())) {
                task.setIsActive(false);
                task.setNextRunAt(null);
            } else {
                task.setNextRunAt(calculateNextRunTime(
                        task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now()));
            }
            ContentAutomationTask saved = taskRepository.save(task);
            incrementTemplateUsageAfterSuccess(task, template);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Task executed successfully");
            response.put("task", buildTaskResponse(saved));
            response.put("result", result);
            return response;
        } catch (RuntimeException error) {
            recordFailure(task, startedAt, error);
            if (propagateFailure) {
                throw error;
            }
            log.warn("Scheduled content automation task {} failed: {}", task.getId(), safeMessage(error));
            return failureResponse(task, error);
        }
    }

    private Map<String, Object> executeConfiguredAction(ContentAutomationTask task, Map<String, Object> config) {
        String type = normalizeTaskType(task.getTaskType());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", type);

        if ("GENERATE".equals(type)) {
            ContentAgentRunResult run = contentArticleService.runAgent(task.getUserId(), agentRequest(config));
            result.put("article", run.getArticle());
            result.put("automation", run.getAutomation());
            result.put("topic", run.getTopic());
            result.put("draft", run.getDraft());
            requireSuccessfulArticle(run.getArticle());
            return result;
        }
        if ("GENERATE_ARTICLE".equals(type)) {
            ContentArticleView article = contentArticleService.generate(task.getUserId(), generateRequest(config));
            result.put("article", article);
            requireSuccessfulArticle(article);
            return result;
        }
        if ("BATCH".equals(type)) {
            result.put("articles", executeBatch(task.getUserId(), config));
            return result;
        }
        if ("PUBLISH".equals(type) || "PUBLISH_ARTICLE".equals(type)) {
            Long articleId = longValue(config.get("articleId"));
            if (articleId == null || articleId <= 0) {
                throw new BusinessException(400, "config.articleId is required for publish tasks");
            }
            result.put("publish", contentArticleService.publishWechat(task.getUserId(), articleId));
            Map<?, ?> publish = (Map<?, ?>) result.get("publish");
            Object articleValue = publish.get("article");
            if (!(articleValue instanceof ContentArticleView)
                    || !"PUBLISHED".equals(((ContentArticleView) articleValue).getStatus())) {
                throw new BusinessException(503, "WeChat accepted the publish submission; awaiting confirmation");
            }
            return result;
        }
        throw new BusinessException(400, "Unsupported taskType: " + type);
    }

    private List<ContentArticleView> executeBatch(Long userId, Map<String, Object> config) {
        Object itemsValue = config.containsKey("articles") ? config.get("articles") : config.get("topics");
        List<?> items = itemsValue instanceof List ? (List<?>) itemsValue : Collections.emptyList();
        if (items.isEmpty()) {
            throw new BusinessException(400, "Batch tasks require config.topics or config.articles");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "Batch tasks support at most " + MAX_BATCH_SIZE + " articles");
        }

        List<ContentArticleView> articles = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> itemConfig = new LinkedHashMap<>(config);
            itemConfig.remove("articles");
            itemConfig.remove("topics");
            if (item instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                    if (entry.getKey() != null) {
                        itemConfig.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } else if (item != null) {
                itemConfig.put("topic", String.valueOf(item));
            }
            ContentArticleGenerateRequest request = generateRequest(itemConfig);
            if (request.getTopic() == null || request.getTopic().trim().isEmpty()) {
                throw new BusinessException(400, "Every batch item requires a topic");
            }
            articles.add(contentArticleService.generate(userId, request));
            requireSuccessfulArticle(articles.get(articles.size() - 1));
        }
        return articles;
    }

    private void requireSuccessfulArticle(ContentArticleView article) {
        if (article == null) {
            throw new BusinessException(502, "Content pipeline returned no article");
        }
        boolean localDraft = article.getWechatMediaId() != null
                && article.getWechatMediaId().startsWith("local-");
        if (localDraft || (article.getErrorMessage() != null && !article.getErrorMessage().trim().isEmpty())) {
            throw new BusinessException(502, article.getErrorMessage() == null
                    ? "WeChat delivery fell back to a local draft"
                    : article.getErrorMessage());
        }
    }

    private ContentAgentRunRequest agentRequest(Map<String, Object> config) {
        ContentAgentRunRequest request = new ContentAgentRunRequest();
        request.setCategory(stringValue(config.get("category")));
        request.setTopic(stringValue(config.get("topic")));
        request.setInstruction(stringValue(config.get("instruction")));
        request.setLength(stringValue(config.get("length")));
        request.setGenerateCover(booleanObject(config.get("generateCover")));
        request.setAutoWechatDraft(booleanValue(config.get("autoWechatDraft"), true));
        request.setAutoPublish(booleanValue(config.get("autoPublish"), false));
        return request;
    }

    private ContentArticleGenerateRequest generateRequest(Map<String, Object> config) {
        ContentArticleGenerateRequest request = new ContentArticleGenerateRequest();
        request.setTopic(stringValue(config.get("topic")));
        request.setCategory(stringValue(config.get("category")));
        request.setLayoutTheme(stringValue(config.get("layoutTheme")));
        request.setImageMode(stringValue(config.get("imageMode")));
        request.setResearchEnabled(booleanObject(config.get("researchEnabled")));
        request.setResearchDepth(stringValue(config.get("researchDepth")));
        request.setSearchQueries(stringList(config.get("searchQueries")));
        request.setAutoWechatDraft(booleanValue(config.get("autoWechatDraft"), true));
        request.setAutoPublish(booleanValue(config.get("autoPublish"), false));
        request.setAngle(stringValue(config.get("angle")));
        request.setAudience(stringValue(config.get("audience")));
        request.setTone(stringValue(config.get("tone")));
        request.setLength(stringValue(config.get("length")));
        request.setGenerateCover(booleanObject(config.get("generateCover")));
        request.setCoverStyle(stringValue(config.get("coverStyle")));
        request.setModel(stringValue(config.get("model")));
        return request;
    }

    private void recordFailure(ContentAutomationTask task, LocalDateTime startedAt, RuntimeException error) {
        task.setRunCount(safeCount(task.getRunCount()) + 1);
        task.setFailCount(safeCount(task.getFailCount()) + 1);
        task.setLastRunAt(startedAt);
        task.setRunStartedAt(null);
        task.setStatus("FAILED");
        task.setLastError(safeMessage(error));
        task.setNextRunAt(Boolean.TRUE.equals(task.getIsActive()) ? LocalDateTime.now().plusHours(1) : null);
        taskRepository.save(task);
    }

    private Map<String, Object> failureResponse(ContentAutomationTask task, RuntimeException error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", safeMessage(error));
        response.put("task", buildTaskResponse(task));
        return response;
    }

    private void validateTaskConfiguration(ContentAutomationTask task) {
        normalizeTaskType(task.getTaskType());
        normalizeScheduleType(task.getScheduleType());
        calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now());
        requireActiveOwnedTemplate(task);
        Map<String, Object> config = readConfig(task.getConfigJson());
        if (("PUBLISH".equals(task.getTaskType()) || "PUBLISH_ARTICLE".equals(task.getTaskType()))
                && longValue(config.get("articleId")) == null) {
            throw new BusinessException(400, "config.articleId is required for publish tasks");
        }
        if ("BATCH".equals(task.getTaskType())) {
            Object items = config.containsKey("articles") ? config.get("articles") : config.get("topics");
            if (!(items instanceof List) || ((List<?>) items).isEmpty()) {
                throw new BusinessException(400, "Batch tasks require config.topics or config.articles");
            }
            if (((List<?>) items).size() > MAX_BATCH_SIZE) {
                throw new BusinessException(400, "Batch tasks support at most " + MAX_BATCH_SIZE + " articles");
            }
        }
    }

    private ContentTemplate requireActiveOwnedTemplate(ContentAutomationTask task) {
        if (task.getTemplateId() == null) {
            return null;
        }
        if (task.getTemplateId() <= 0 || task.getUserId() == null) {
            throw new BusinessException(400, "templateId must reference an active template owned by the task user");
        }
        return templateRepository.findByIdAndUserId(task.getTemplateId(), task.getUserId())
                .filter(template -> Boolean.TRUE.equals(template.getIsActive()))
                .orElseThrow(() -> new BusinessException(
                        400, "templateId must reference an active template owned by the task user"));
    }

    private Map<String, Object> applyTemplateDefaults(Map<String, Object> explicitConfig,
                                                       ContentTemplate template) {
        Map<String, Object> effective = new LinkedHashMap<>(explicitConfig);
        if (template == null) {
            return effective;
        }
        putDefault(effective, "category", template.getCategory());
        putDefault(effective, "angle", template.getDefaultAngle());
        putDefault(effective, "audience", template.getDefaultAudience());
        putDefault(effective, "tone", template.getDefaultTone());
        putDefault(effective, "coverStyle", template.getCoverStyle());
        putDefault(effective, "layoutTheme", template.getLayoutTheme());
        putDefault(effective, "imageMode", template.getImageMode());
        putDefault(effective, "instruction", firstText(
                template.getPromptTemplate(), template.getContentTemplate()));
        return effective;
    }

    private void putDefault(Map<String, Object> config, String key, String value) {
        if (!config.containsKey(key) && value != null && !value.trim().isEmpty()) {
            config.put(key, value.trim());
        }
    }

    private String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    private void incrementTemplateUsageAfterSuccess(ContentAutomationTask task, ContentTemplate template) {
        if (template == null || !isGenerationTask(task.getTaskType())) {
            return;
        }
        try {
            int updated = templateRepository.incrementUsageCount(template.getId(), task.getUserId());
            if (updated == 0) {
                log.warn("Template {} became unavailable before usage could be recorded", template.getId());
            }
        } catch (RuntimeException error) {
            log.warn("Could not record usage for content template {}: {}",
                    template.getId(), safeMessage(error));
        }
    }

    private boolean isGenerationTask(String taskType) {
        String type = normalizeTaskType(taskType);
        return "GENERATE".equals(type) || "GENERATE_ARTICLE".equals(type) || "BATCH".equals(type);
    }

    private LocalDateTime calculateNextRunTime(String scheduleType, String expression, LocalDateTime from) {
        String type = normalizeScheduleType(scheduleType);
        if ("ONCE".equals(type)) {
            if (expression == null || expression.trim().isEmpty()) {
                return from;
            }
            try {
                return LocalDateTime.parse(expression.trim());
            } catch (Exception error) {
                throw new BusinessException(400, "ONCE scheduleExpression must be an ISO local date-time");
            }
        }
        if ("HOURLY".equals(type)) {
            return from.plusHours(1);
        }
        if ("DAILY".equals(type)) {
            if (expression != null && !expression.trim().isEmpty()) {
                try {
                    LocalTime time = LocalTime.parse(expression.trim());
                    LocalDateTime next = from.toLocalDate().atTime(time);
                    return next.isAfter(from) ? next : next.plusDays(1);
                } catch (Exception error) {
                    throw new BusinessException(400, "DAILY scheduleExpression must use HH:mm[:ss]");
                }
            }
            return from.plusDays(1);
        }
        if ("WEEKLY".equals(type)) {
            return from.plusWeeks(1);
        }
        if ("MONTHLY".equals(type)) {
            return from.plusMonths(1);
        }
        if ("CUSTOM".equals(type)) {
            return addCustomInterval(from, expression);
        }
        throw new BusinessException(400, "Unsupported scheduleType: " + scheduleType);
    }

    private LocalDateTime addCustomInterval(LocalDateTime from, String expression) {
        if (expression == null || !expression.trim().matches("[1-9][0-9]*[mhdw]")) {
            throw new BusinessException(400, "CUSTOM scheduleExpression must look like 30m, 2h, 7d, or 1w");
        }
        String value = expression.trim();
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        char unit = value.charAt(value.length() - 1);
        if (unit == 'm') return from.plusMinutes(amount);
        if (unit == 'h') return from.plusHours(amount);
        if (unit == 'd') return from.plusDays(amount);
        return from.plusWeeks(amount);
    }

    private ContentAutomationTask requireOwnedTask(Long userId, Long taskId) {
        requireUserId(userId);
        if (taskId == null) {
            throw new BusinessException(400, "taskId is required");
        }
        return taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(404, "Task not found"));
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
    }

    private void requireRequest(Map<String, Object> request) {
        if (request == null) {
            throw new BusinessException(400, "Request body is required");
        }
    }

    private String normalizeTaskType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TASK_TYPES.contains(type)) {
            throw new BusinessException(400, "Unsupported taskType: " + value);
        }
        return type;
    }

    private String normalizeScheduleType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SCHEDULE_TYPES.contains(type)) {
            throw new BusinessException(400, "Unsupported scheduleType: " + value);
        }
        return type;
    }

    private String writeConfig(Object config) {
        Object value = config == null ? Collections.emptyMap() : config;
        if (!(value instanceof Map)) {
            throw new BusinessException(400, "config must be a JSON object");
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(400, "config must be valid JSON");
        }
    }

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new BusinessException(400, "Stored task config is invalid JSON");
        }
    }

    private String requiredText(Map<String, Object> map, String key, int maxLength) {
        String value = optionalText(map, key, maxLength);
        if (value == null) {
            throw new BusinessException(400, key + " is required");
        }
        return value;
    }

    private String optionalText(Map<String, Object> map, String key, int maxLength) {
        Object raw = map.get(key);
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) {
            throw new BusinessException(400, key + " is too long");
        }
        return value;
    }

    private Long optionalLong(Map<String, Object> map, String key) {
        Long value = longValue(map.get(key));
        if (map.get(key) != null && value == null) {
            throw new BusinessException(400, key + " must be a number");
        }
        return value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanObject(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw new BusinessException(400, "Boolean config values must be true or false");
    }

    private boolean booleanValue(Object value, boolean fallback) {
        Boolean parsed = booleanObject(value);
        return parsed == null ? fallback : parsed;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List)) return null;
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            String text = stringValue(item);
            if (text != null) result.add(text);
        }
        return result;
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "Unknown error" : error.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1***");
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private Map<String, Object> buildTaskResponse(ContentAutomationTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("userId", task.getUserId());
        result.put("taskName", task.getTaskName());
        result.put("taskType", task.getTaskType());
        result.put("scheduleType", task.getScheduleType());
        result.put("scheduleExpression", task.getScheduleExpression());
        result.put("templateId", task.getTemplateId());
        result.put("config", readConfig(task.getConfigJson()));
        result.put("status", task.getStatus());
        result.put("lastRunAt", task.getLastRunAt());
        result.put("nextRunAt", task.getNextRunAt());
        result.put("runCount", safeCount(task.getRunCount()));
        result.put("successCount", safeCount(task.getSuccessCount()));
        result.put("failCount", safeCount(task.getFailCount()));
        result.put("lastError", task.getLastError());
        result.put("isActive", task.getIsActive());
        result.put("createdAt", task.getCreatedAt());
        result.put("updatedAt", task.getUpdatedAt());
        return result;
    }

    private Map<String, Object> buildTaskListItem(ContentAutomationTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("taskType", task.getTaskType());
        result.put("scheduleType", task.getScheduleType());
        result.put("status", task.getStatus());
        result.put("lastRunAt", task.getLastRunAt());
        result.put("nextRunAt", task.getNextRunAt());
        result.put("runCount", safeCount(task.getRunCount()));
        result.put("successCount", safeCount(task.getSuccessCount()));
        result.put("failCount", safeCount(task.getFailCount()));
        result.put("isActive", task.getIsActive());
        result.put("updatedAt", task.getUpdatedAt());
        return result;
    }
}
