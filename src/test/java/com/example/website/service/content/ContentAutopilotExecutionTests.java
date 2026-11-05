package com.example.website.service.content;

import com.example.website.common.BusinessException;
import com.example.website.dto.content.ContentAgentRunRequest;
import com.example.website.dto.content.ContentAgentRunResult;
import com.example.website.dto.content.ContentArticleView;
import com.example.website.service.SysConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentAutopilotExecutionTests {

    @Test
    void dueSlotRunsOnlyWhenDurableSlotIsClaimed() {
        Fixture fixture = fixture();
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 8, 30);
        LocalDateTime slot = LocalDateTime.of(2026, 9, 19, 8, 0);
        when(fixture.slots.claim(7L, slot, now)).thenReturn(null);

        fixture.service.runOnce(now);

        verify(fixture.articles, never()).runAgent(eq(7L), any(ContentAgentRunRequest.class));
    }

    @Test
    void failedDeliveryPersistsArticleIdForRetryWithoutGeneratingAgain() {
        Fixture fixture = fixture();
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 8, 30);
        LocalDateTime slot = LocalDateTime.of(2026, 9, 19, 8, 0);
        when(fixture.slots.claim(7L, slot, now)).thenReturn("claim");
        when(fixture.slots.articleId(7L, slot)).thenReturn(null);
        ContentArticleView article = new ContentArticleView();
        article.setId(42L);
        article.setErrorMessage("WeChat unavailable");
        when(fixture.articles.runAgent(eq(7L), any(ContentAgentRunRequest.class)))
                .thenReturn(new ContentAgentRunResult(article, null, null, null));

        fixture.service.runOnce(now);

        verify(fixture.slots).fail(7L, slot, "claim", 42L, "WeChat unavailable");
        verify(fixture.slots, never()).complete(eq(7L), eq(slot), eq("claim"), any());
    }

    @Test
    void enablingRejectsMissingDependenciesWithoutWritingConfiguration() {
        Fixture fixture = fixture(false);
        Map<String, Object> request = new HashMap<>();
        request.put("enabled", true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.updateSettings(7L, request));

        assertEquals(400, error.getCode());
        verify(fixture.config, never()).upsertByKey(any(), any(), any());
    }

    @Test
    void nextRunAtWrapsToTomorrowAfterLastSlot() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 21, 0);
        assertEquals(LocalDateTime.of(2026, 9, 20, 8, 0),
                ContentAutopilotService.nextRunAt(Arrays.asList(LocalTime.of(8, 0), LocalTime.of(20, 0)), now));
    }

    private Fixture fixture() {
        return fixture(true);
    }

    private Fixture fixture(boolean dependenciesReady) {
        SysConfigService config = mock(SysConfigService.class);
        ContentArticleService articles = mock(ContentArticleService.class);
        ContentAgentJobService jobs = mock(ContentAgentJobService.class);
        ContentAutopilotSlotService slots = mock(ContentAutopilotSlotService.class);
        Map<String, String> values = new HashMap<>();
        values.put("content.autopilot.enabled", "true");
        values.put("content.autopilot.userId", "7");
        values.put("content.autopilot.times", "08:00");
        values.put("content.autopilot.categories", "科技 / 互联网");
        if (dependenciesReady) {
            values.put("ai.chat.baseUrl", "https://ai.example.test/v1");
            values.put("ai.chat.apiKey", "test-ai-key");
            values.put("image.api.baseUrl", "https://image.example.test/v1");
            values.put("image.api.key", "test-image-key");
            values.put("wechat.appId", "test-app-id");
            values.put("wechat.appSecret", "test-app-secret");
        }
        when(config.getValue(any())).thenAnswer(invocation ->
                Optional.ofNullable(values.get(invocation.getArgument(0))));
        return new Fixture(new ContentAutopilotService(config, articles, jobs, slots), config, articles, slots);
    }

    private static class Fixture {
        final ContentAutopilotService service;
        final SysConfigService config;
        final ContentArticleService articles;
        final ContentAutopilotSlotService slots;

        Fixture(ContentAutopilotService service, SysConfigService config,
                ContentArticleService articles, ContentAutopilotSlotService slots) {
            this.service = service;
            this.config = config;
            this.articles = articles;
            this.slots = slots;
        }
    }
}
