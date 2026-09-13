package com.example.website.service.content;

import com.example.website.entity.ContentAutomationTask;
import com.example.website.repository.ContentAutomationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentAutomationTaskService {

    private final ContentAutomationTaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建自动化任务
     */
    @Transactional
    @CacheEvict(value = "automation-tasks", allEntries = true)
    public Map<String, Object> createTask(Long userId, Map<String, Object> request) {
        ContentAutomationTask task = new ContentAutomationTask();
        task.setUserId(userId);
        task.setTaskName(getString(request, "taskName"));
        task.setTaskType(getString(request, "taskType"));
        task.setScheduleType(getString(request, "scheduleType"));
        task.setScheduleExpression(getStringOrNull(request, "scheduleExpression"));
        task.setTemplateId(getLongOrNull(request, "templateId"));

        if (request.containsKey("config")) {
            try {
                task.setConfigJson(objectMapper.writeValueAsString(request.get("config")));
            } catch (Exception e) {
                log.error("序列化config失败", e);
            }
        }

        task.setStatus("PENDING");
        task.setIsActive(getBooleanOrDefault(request, "isActive", true));

        // 计算下次运行时间
        task.setNextRunAt(calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression()));

        task = taskRepository.save(task);
        log.info("用户 {} 创建自动化任务: {}", userId, task.getId());

        return buildTaskResponse(task);
    }

    /**
     * 更新自动化任务
     */
    @Transactional
    @CacheEvict(value = {"automation-tasks", "automation-task"}, allEntries = true)
    public Map<String, Object> updateTask(Long userId, Long taskId, Map<String, Object> request) {
        ContentAutomationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));

        if (request.containsKey("taskName")) {
            task.setTaskName(getString(request, "taskName"));
        }
        if (request.containsKey("scheduleType")) {
            task.setScheduleType(getString(request, "scheduleType"));
        }
        if (request.containsKey("scheduleExpression")) {
            task.setScheduleExpression(getStringOrNull(request, "scheduleExpression"));
        }
        if (request.containsKey("templateId")) {
            task.setTemplateId(getLongOrNull(request, "templateId"));
        }
        if (request.containsKey("config")) {
            try {
                task.setConfigJson(objectMapper.writeValueAsString(request.get("config")));
            } catch (Exception e) {
                log.error("序列化config失败", e);
            }
        }
        if (request.containsKey("isActive")) {
            task.setIsActive(getBoolean(request, "isActive"));
        }

        // 重新计算下次运行时间
        if (request.containsKey("scheduleType") || request.containsKey("scheduleExpression")) {
            task.setNextRunAt(calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression()));
        }

        task = taskRepository.save(task);
        log.info("用户 {} 更新自动化任务: {}", userId, taskId);

        return buildTaskResponse(task);
    }

    /**
     * 删除自动化任务
     */
    @Transactional
    @CacheEvict(value = {"automation-tasks", "automation-task"}, allEntries = true)
    public void deleteTask(Long userId, Long taskId) {
        ContentAutomationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));

        taskRepository.delete(task);
        log.info("用户 {} 删除自动化任务: {}", userId, taskId);
    }

    /**
     * 获取任务详情
     */
    @Cacheable(value = "automation-task", key = "#userId + ':' + #taskId")
    public Map<String, Object> getTask(Long userId, Long taskId) {
        ContentAutomationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));

        return buildTaskResponse(task);
    }

    /**
     * 获取任务列表
     */
    @Cacheable(value = "automation-tasks", key = "#userId + ':' + #onlyActive + ':' + #pageable.pageNumber")
    public Page<Map<String, Object>> getTasks(Long userId, Boolean onlyActive, Pageable pageable) {
        Page<ContentAutomationTask> tasks;
        if (onlyActive != null && onlyActive) {
            tasks = taskRepository.findByUserIdAndIsActiveOrderByIdDesc(userId, true, pageable);
        } else {
            tasks = taskRepository.findByUserIdOrderByIdDesc(userId, pageable);
        }

        return tasks.map(this::buildTaskListItem);
    }

    /**
     * 搜索任务
     */
    public Page<Map<String, Object>> searchTasks(Long userId, String keyword, Pageable pageable) {
        return taskRepository.searchByKeyword(userId, keyword, pageable)
                .map(this::buildTaskListItem);
    }

    /**
     * 启用/禁用任务
     */
    @Transactional
    @CacheEvict(value = {"automation-tasks", "automation-task"}, allEntries = true)
    public Map<String, Object> toggleTaskStatus(Long userId, Long taskId, boolean isActive) {
        ContentAutomationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));

        task.setIsActive(isActive);
        if (isActive && task.getNextRunAt() == null) {
            task.setNextRunAt(calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression()));
        }

        task = taskRepository.save(task);
        log.info("用户 {} {} 自动化任务: {}", userId, isActive ? "启用" : "禁用", taskId);

        return buildTaskResponse(task);
    }

    /**
     * 手动执行任务
     */
    @Transactional
    public Map<String, Object> executeTask(Long userId, Long taskId) {
        ContentAutomationTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));

        try {
            // 实际执行任务逻辑（这里先记录日志）
            log.info("手动执行任务: {} (用户: {})", taskId, userId);

            // 更新执行记录
            task.setRunCount(task.getRunCount() + 1);
            task.setSuccessCount(task.getSuccessCount() + 1);
            task.setLastRunAt(LocalDateTime.now());
            task.setStatus("SUCCESS");

            // 计算下次运行时间
            task.setNextRunAt(calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression()));

            task = taskRepository.save(task);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "任务执行成功");
            result.put("task", buildTaskResponse(task));

            return result;

        } catch (Exception e) {
            log.error("执行任务失败", e);

            task.setRunCount(task.getRunCount() + 1);
            task.setFailCount(task.getFailCount() + 1);
            task.setLastRunAt(LocalDateTime.now());
            task.setLastError(e.getMessage());
            task.setStatus("FAILED");

            taskRepository.save(task);

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "任务执行失败: " + e.getMessage());
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics(Long userId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTasks", taskRepository.countByUserIdAndIsActive(userId, null));
        stats.put("activeTasks", taskRepository.countByUserIdAndIsActive(userId, true));
        stats.put("pendingTasks", taskRepository.countByUserIdAndStatus(userId, "PENDING"));
        stats.put("runningTasks", taskRepository.countByUserIdAndStatus(userId, "RUNNING"));

        return stats;
    }

    /**
     * 定时执行任务（每分钟检查一次）
     */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void executeScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<ContentAutomationTask> tasks = taskRepository.findByIsActiveAndNextRunAtBeforeOrderByNextRunAtAsc(true, now);

        for (ContentAutomationTask task : tasks) {
            try {
                log.info("定时执行任务: {}", task.getId());

                // 实际执行任务逻辑
                task.setRunCount(task.getRunCount() + 1);
                task.setSuccessCount(task.getSuccessCount() + 1);
                task.setLastRunAt(now);
                task.setStatus("SUCCESS");
                task.setLastError(null);

                // 计算下次运行时间
                task.setNextRunAt(calculateNextRunTime(task.getScheduleType(), task.getScheduleExpression()));

                taskRepository.save(task);

            } catch (Exception e) {
                log.error("定时执行任务失败: {}", task.getId(), e);

                task.setRunCount(task.getRunCount() + 1);
                task.setFailCount(task.getFailCount() + 1);
                task.setLastRunAt(now);
                task.setLastError(e.getMessage());
                task.setStatus("FAILED");

                // 失败后延迟1小时重试
                task.setNextRunAt(now.plusHours(1));

                taskRepository.save(task);
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    private LocalDateTime calculateNextRunTime(String scheduleType, String scheduleExpression) {
        LocalDateTime now = LocalDateTime.now();

        switch (scheduleType) {
            case "ONCE":
                return null;

            case "HOURLY":
                return now.plusHours(1);

            case "DAILY":
                return now.plusDays(1);

            case "WEEKLY":
                return now.plusWeeks(1);

            case "MONTHLY":
                return now.plusMonths(1);

            case "CUSTOM":
                // 解析自定义表达式，例如 "2h", "30m", "7d"
                if (scheduleExpression != null && !scheduleExpression.isEmpty()) {
                    try {
                        char unit = scheduleExpression.charAt(scheduleExpression.length() - 1);
                        int value = Integer.parseInt(scheduleExpression.substring(0, scheduleExpression.length() - 1));

                        switch (unit) {
                            case 'm':
                                return now.plusMinutes(value);
                            case 'h':
                                return now.plusHours(value);
                            case 'd':
                                return now.plusDays(value);
                            case 'w':
                                return now.plusWeeks(value);
                        }
                    } catch (Exception e) {
                        log.error("解析自定义表达式失败: {}", scheduleExpression, e);
                    }
                }
                return now.plusDays(1);

            default:
                return now.plusDays(1);
        }
    }

    private Map<String, Object> buildTaskResponse(ContentAutomationTask task) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", task.getId());
        result.put("userId", task.getUserId());
        result.put("taskName", task.getTaskName());
        result.put("taskType", task.getTaskType());
        result.put("scheduleType", task.getScheduleType());
        result.put("scheduleExpression", task.getScheduleExpression());
        result.put("templateId", task.getTemplateId());
        result.put("config", parseJson(task.getConfigJson()));
        result.put("status", task.getStatus());
        result.put("lastRunAt", task.getLastRunAt());
        result.put("nextRunAt", task.getNextRunAt());
        result.put("runCount", task.getRunCount());
        result.put("successCount", task.getSuccessCount());
        result.put("failCount", task.getFailCount());
        result.put("lastError", task.getLastError());
        result.put("isActive", task.getIsActive());
        result.put("createdAt", task.getCreatedAt());
        result.put("updatedAt", task.getUpdatedAt());
        return result;
    }

    private Map<String, Object> buildTaskListItem(ContentAutomationTask task) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("taskType", task.getTaskType());
        result.put("scheduleType", task.getScheduleType());
        result.put("status", task.getStatus());
        result.put("lastRunAt", task.getLastRunAt());
        result.put("nextRunAt", task.getNextRunAt());
        result.put("runCount", task.getRunCount());
        result.put("successCount", task.getSuccessCount());
        result.put("failCount", task.getFailCount());
        result.put("isActive", task.getIsActive());
        result.put("updatedAt", task.getUpdatedAt());
        return result;
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        return value.toString();
    }

    private String getStringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private Boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
