package com.example.website.service.content;

import com.example.website.common.BusinessException;
import com.example.website.dto.content.ContentArticleGenerateRequest;
import com.example.website.dto.content.ContentArticleView;
import com.example.website.entity.ContentAutomationTask;
import com.example.website.entity.ContentTemplate;
import com.example.website.repository.ContentAutomationTaskRepository;
import com.example.website.repository.ContentTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentAutomationTaskServiceTests {

    private ContentAutomationTaskRepository repository;
    private ContentTemplateRepository templates;
    private ContentArticleService articles;
    private ContentAutomationTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContentAutomationTaskRepository.class);
        templates = mock(ContentTemplateRepository.class);
        articles = mock(ContentArticleService.class);
        service = new ContentAutomationTaskService(repository, templates, articles, new ObjectMapper());
    }

    @Test
    void manualGenerateExecutesArticlePipelineAndRecordsResult() {
        ContentAutomationTask task = task("GENERATE_ARTICLE", "{\"topic\":\"Release notes\",\"autoWechatDraft\":false}");
        when(repository.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(task));
        when(repository.claimTask(eq(3L), any(), any())).thenReturn(1);
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContentArticleView article = new ContentArticleView();
        article.setId(11L);
        article.setTitle("Release notes");
        article.setStatus("DRAFT");
        when(articles.generate(eq(7L), any(ContentArticleGenerateRequest.class))).thenReturn(article);

        Map<String, Object> response = service.executeTask(7L, 3L);

        assertEquals(true, response.get("success"));
        assertEquals(article, ((Map<?, ?>) response.get("result")).get("article"));
        assertEquals("SUCCESS", task.getStatus());
        assertEquals(1, task.getRunCount());
        assertEquals(1, task.getSuccessCount());
        assertEquals(false, task.getIsActive());
        verify(articles).generate(eq(7L), any(ContentArticleGenerateRequest.class));
    }

    @Test
    void localWechatFallbackCountsAsFailureAndKeepsRetrySchedule() {
        ContentAutomationTask task = task("GENERATE_ARTICLE", "{\"topic\":\"Release notes\"}");
        task.setScheduleType("DAILY");
        when(repository.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(task));
        when(repository.claimTask(eq(3L), any(), any())).thenReturn(1);
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContentArticleView article = new ContentArticleView();
        article.setId(11L);
        article.setStatus("DRAFT");
        article.setErrorMessage("WeChat draft creation failed");
        when(articles.generate(eq(7L), any(ContentArticleGenerateRequest.class))).thenReturn(article);

        BusinessException error = assertThrows(BusinessException.class, () -> service.executeTask(7L, 3L));

        assertEquals(502, error.getCode());
        assertEquals("FAILED", task.getStatus());
        assertEquals(1, task.getRunCount());
        assertEquals(1, task.getFailCount());
        assertEquals(0, task.getSuccessCount());
        assertTrue(task.getNextRunAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void scheduledExecutionClaimsDueTaskBeforeWritingArticle() {
        ContentAutomationTask task = task("GENERATE_ARTICLE", "{\"topic\":\"Daily report\",\"autoWechatDraft\":false}");
        task.setScheduleType("DAILY");
        task.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findByIsActiveAndNextRunAtLessThanEqualOrderByNextRunAtAsc(eq(true), any()))
                .thenReturn(Collections.singletonList(task));
        when(repository.claimDueTask(eq(3L), any(), any())).thenReturn(0);

        service.executeScheduledTasks();

        verify(articles, never()).generate(any(), any(ContentArticleGenerateRequest.class));
    }

    @Test
    void invalidTaskTypeFailsBeforeSaving() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskName", "Invalid task");
        request.put("taskType", "SHELL_COMMAND");
        request.put("scheduleType", "DAILY");

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createTask(7L, request)).getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsTemplateNotOwnedByUser() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskName", "Daily content");
        request.put("taskType", "GENERATE_ARTICLE");
        request.put("scheduleType", "DAILY");
        request.put("templateId", 9L);
        when(templates.findByIdAndUserId(9L, 7L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTask(7L, request));

        assertEquals(400, error.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsInactiveTemplate() {
        ContentAutomationTask task = task("GENERATE_ARTICLE", "{\"topic\":\"Release notes\"}");
        ContentTemplate inactive = template(9L, false);
        when(repository.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(task));
        when(templates.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(inactive));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("templateId", 9L);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateTask(7L, 3L, request));

        assertEquals(400, error.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void executionAppliesTemplateDefaultsWithoutOverridingExplicitConfig() {
        ContentAutomationTask task = task("GENERATE_ARTICLE",
                "{\"topic\":\"Release notes\",\"category\":\"Explicit category\",\"autoWechatDraft\":false}");
        task.setTemplateId(9L);
        ContentTemplate template = template(9L, true);
        template.setCategory("Template category");
        template.setDefaultAngle("Template angle");
        template.setDefaultAudience("Template audience");
        template.setDefaultTone("Template tone");
        template.setCoverStyle("Editorial cover");
        template.setLayoutTheme("magazine");
        template.setImageMode("search");
        when(repository.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(task));
        when(repository.claimTask(eq(3L), any(), any())).thenReturn(1);
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(templates.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(template));
        when(templates.incrementUsageCount(9L, 7L)).thenReturn(1);
        ContentArticleView article = new ContentArticleView();
        article.setId(11L);
        article.setStatus("DRAFT");
        when(articles.generate(eq(7L), any(ContentArticleGenerateRequest.class))).thenReturn(article);

        service.executeTask(7L, 3L);

        verify(articles).generate(eq(7L), org.mockito.ArgumentMatchers.argThat(request ->
                "Explicit category".equals(request.getCategory())
                        && "Template angle".equals(request.getAngle())
                        && "Template audience".equals(request.getAudience())
                        && "Template tone".equals(request.getTone())
                        && "Editorial cover".equals(request.getCoverStyle())
                        && "magazine".equals(request.getLayoutTheme())
                        && "search".equals(request.getImageMode())));
        verify(templates, times(1)).incrementUsageCount(9L, 7L);
    }

    @Test
    void failedExecutionDoesNotIncrementTemplateUsage() {
        ContentAutomationTask task = task("GENERATE_ARTICLE", "{\"topic\":\"Release notes\"}");
        task.setTemplateId(9L);
        ContentTemplate template = template(9L, true);
        when(repository.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(task));
        when(repository.claimTask(eq(3L), any(), any())).thenReturn(1);
        when(repository.findById(3L)).thenReturn(Optional.of(task));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(templates.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(template));
        doThrow(new BusinessException(502, "generation failed"))
                .when(articles).generate(eq(7L), any(ContentArticleGenerateRequest.class));

        assertThrows(BusinessException.class, () -> service.executeTask(7L, 3L));

        verify(templates, never()).incrementUsageCount(any(), any());
    }

    private ContentAutomationTask task(String type, String config) {
        ContentAutomationTask task = new ContentAutomationTask();
        task.setId(3L);
        task.setUserId(7L);
        task.setTaskName("Daily content");
        task.setTaskType(type);
        task.setScheduleType("ONCE");
        task.setConfigJson(config);
        task.setIsActive(true);
        return task;
    }

    private ContentTemplate template(Long id, boolean active) {
        ContentTemplate template = new ContentTemplate();
        template.setId(id);
        template.setUserId(7L);
        template.setName("Template");
        template.setContentTemplate("Content instructions");
        template.setIsActive(active);
        return template;
    }
}
