package com.example.website.dto.content;

import com.example.website.entity.ContentArticle;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ContentArticleView {
    private Long id;
    private String title;
    private String digest;
    private String contentMarkdown;
    private String contentHtml;
    private String coverPrompt;
    private String coverImageUrl;
    private List<Map<String, Object>> topics;
    private List<String> tags;
    private List<String> riskTips;
    private String model;
    private String category;
    private String layoutTheme;
    private String imageMode;
    private Object automation;
    private Object plan;
    private List<Map<String, Object>> evidenceSources;
    private Object review;
    private Integer qualityScore;
    private String status;
    private String wechatMediaId;
    private String wechatPublishId;
    private String wechatUrl;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ContentArticleView from(ContentArticle article,
                                          List<Map<String, Object>> topics,
                                          List<String> tags,
                                          List<String> riskTips,
                                          Object automation) {
        return from(article, topics, tags, riskTips, automation, null, null, null);
    }

    public static ContentArticleView from(ContentArticle article,
                                          List<Map<String, Object>> topics,
                                          List<String> tags,
                                          List<String> riskTips,
                                          Object automation,
                                          Object plan,
                                          List<Map<String, Object>> evidenceSources,
                                          Object review) {
        ContentArticleView view = new ContentArticleView();
        view.setId(article.getId());
        view.setTitle(article.getTitle());
        view.setDigest(article.getDigest());
        view.setContentMarkdown(article.getContentMarkdown());
        view.setContentHtml(article.getContentHtml());
        view.setCoverPrompt(article.getCoverPrompt());
        view.setCoverImageUrl(article.getCoverImageUrl());
        view.setTopics(topics);
        view.setTags(tags);
        view.setRiskTips(riskTips);
        view.setModel(article.getModel());
        view.setCategory(article.getCategory());
        view.setLayoutTheme(article.getLayoutTheme());
        view.setImageMode(article.getImageMode());
        view.setAutomation(automation);
        view.setPlan(plan);
        view.setEvidenceSources(evidenceSources);
        view.setReview(review);
        view.setQualityScore(article.getQualityScore());
        view.setStatus(article.getStatus());
        view.setWechatMediaId(article.getWechatMediaId());
        view.setWechatPublishId(article.getWechatPublishId());
        view.setWechatUrl(article.getWechatUrl());
        view.setErrorMessage(article.getErrorMessage());
        view.setCreatedAt(article.getCreatedAt());
        view.setUpdatedAt(article.getUpdatedAt());
        return view;
    }
}
