package com.example.website.module.content.application;

import com.example.website.common.BusinessException;
import com.example.website.module.content.api.dto.ContentArticleUpdateRequest;
import com.example.website.module.content.domain.ContentArticle;
import com.example.website.module.content.infrastructure.persistence.ContentArticleRepository;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentWechatDeliveryTests {

    private ContentArticleRepository repository;
    private SysConfigService config;
    private WechatOfficialAccountClient wechat;
    private ContentPipelineService pipeline;
    private ContentAutomationBuilder builder;
    private ContentArticleService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContentArticleRepository.class);
        config = mock(SysConfigService.class);
        wechat = mock(WechatOfficialAccountClient.class);
        pipeline = mock(ContentPipelineService.class);
        builder = mock(ContentAutomationBuilder.class);
        service = new ContentArticleService(repository, config, new ObjectMapper(), wechat,
                null, builder, null, pipeline, null, null, null, null, null);
        when(repository.save(any(ContentArticle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(config.getValue(any())).thenAnswer(invocation -> Optional.ofNullable(settings().get(invocation.getArgument(0))));
    }

    @Test
    void existingWechatDraftIsNotSubmittedAgain() {
        ContentArticle article = article(ContentArticle.STATUS_WECHAT_DRAFT);
        article.setWechatMediaId("genuine-media-id");
        when(repository.findLockedByIdAndUserId(9L, 7L)).thenReturn(Optional.of(article));

        Map<String, Object> response = service.createWechatDraft(7L, 9L);

        assertEquals("genuine-media-id", ((Map<?, ?>) response.get("draft")).get("mediaId"));
        verify(wechat, never()).addDraft(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void editingWechatDraftInvalidatesRemoteMediaId() {
        ContentArticle article = article(ContentArticle.STATUS_WECHAT_DRAFT);
        article.setWechatMediaId("genuine-media-id");
        when(repository.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(article));
        when(pipeline.evaluateArticle(article)).thenReturn(new PublishableArticleContract.Evaluation(
                "pass", 90, true, Collections.emptyList(), new LinkedHashMap<>()));
        ContentArticleUpdateRequest update = new ContentArticleUpdateRequest();
        update.setTitle("Updated title");
        update.setContentMarkdown("# Updated title\n\nNew body");

        service.update(7L, 9L, update);

        assertEquals(ContentArticle.STATUS_DRAFT, article.getStatus());
        assertNull(article.getWechatMediaId());
        assertNull(article.getWechatPublishId());
    }

    @Test
    void submittedArticleCannotBeEdited() {
        ContentArticle article = article(ContentArticle.STATUS_PUBLISH_SUBMITTED);
        when(repository.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(article));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(7L, 9L, new ContentArticleUpdateRequest()));

        assertEquals(409, error.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void acceptedPublishRemainsPendingUntilWechatConfirmsIt() {
        ContentArticle article = article(ContentArticle.STATUS_WECHAT_DRAFT);
        article.setWechatMediaId("genuine-media-id");
        when(repository.findLockedByIdAndUserId(9L, 7L)).thenReturn(Optional.of(article));
        when(wechat.submitPublish("app-id", "secret", "genuine-media-id"))
                .thenReturn(new WechatOfficialAccountClient.PublishResult("publish-id"));
        when(wechat.getPublishStatus("app-id", "secret", "publish-id"))
                .thenReturn(new WechatOfficialAccountClient.PublishStatusResult(1, null, null, null, null))
                .thenReturn(new WechatOfficialAccountClient.PublishStatusResult(
                        0, "wechat-article", "https://mp.weixin.qq.com/s/real", null, null));

        service.publishWechat(7L, 9L);
        assertEquals(ContentArticle.STATUS_PUBLISH_SUBMITTED, article.getStatus());
        service.publishWechat(7L, 9L);
        assertEquals(ContentArticle.STATUS_PUBLISHED, article.getStatus());
        assertEquals("https://mp.weixin.qq.com/s/real", article.getWechatUrl());
        verify(wechat).submitPublish("app-id", "secret", "genuine-media-id");
    }

    private ContentArticle article(String status) {
        ContentArticle article = new ContentArticle();
        article.setId(9L);
        article.setUserId(7L);
        article.setStatus(status);
        article.setTitle("Article title");
        article.setContentMarkdown("# Article title\n\nBody");
        article.setContentHtml("<h1>Article title</h1><p>Body</p>");
        article.setQualityScore(90);
        return article;
    }

    private Map<String, String> settings() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("wechat.freePublishEnabled", "true");
        values.put("wechat.appId", "app-id");
        values.put("wechat.appSecret", "secret");
        return values;
    }
}
