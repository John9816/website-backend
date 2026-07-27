package com.example.website.service.content;

import com.example.website.dto.content.ContentAutomationView;
import com.example.website.entity.ContentArticle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.hasText;

/**
 * Builds the automation view (logs / jobs / publish records) attached to a content article.
 *
 * <p>Quality-gate summary is derived from {@link PublishableArticleContract} so the admin UI
 * shows the same pass/block decision used by draft/publish orchestration.
 */
@Component
@RequiredArgsConstructor
public class ContentAutomationBuilder {

    private final PublishableArticleContract publishableArticleContract;

    public ContentAutomationView buildAutomation(ContentArticle article, String errorMessage) {
        String stage = ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) ? "publish"
                : ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus()) ? "wechat_draft"
                : hasText(errorMessage) ? "quality_gate" : "draft_ready";
        return buildAutomation(article, errorMessage, stage);
    }

    public ContentAutomationView buildAutomation(ContentArticle article, String errorMessage, String currentStage) {
        String now = LocalDateTime.now().toString();
        List<Object> logs = new ArrayList<>();
        logs.add(logItem(article.getId() + "-generate", "generate", "SUCCESS", "文章内容已生成", null, valueOrNow(article.getCreatedAt(), now)));
        if (ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus()) || ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "wechat_draft".equals(currentStage)) {
            logs.add(logItem(article.getId() + "-wechat-draft", "wechat_draft", errorMessage == null ? "SUCCESS" : "FAILED",
                    errorMessage == null ? "微信草稿已创建" : "微信草稿创建失败", errorMessage, now));
        }
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "publish".equals(currentStage)) {
            logs.add(logItem(article.getId() + "-publish", "publish", errorMessage == null ? "SUCCESS" : "FAILED",
                    errorMessage == null ? "微信发布已提交" : "微信发布提交失败", errorMessage, now));
        }

        List<Object> jobs = new ArrayList<>();
        jobs.add(jobItem(article.getId() + "-wechat-draft-job", "wechat_draft",
                errorMessage != null && "wechat_draft".equals(currentStage) ? "FAILED" : "SUCCESS", errorMessage, now));
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "publish".equals(currentStage)) {
            jobs.add(jobItem(article.getId() + "-publish-job", "publish",
                    errorMessage != null && "publish".equals(currentStage) ? "FAILED" : "SUCCESS", errorMessage, now));
        }

        List<Object> records = new ArrayList<>();
        if (hasText(article.getWechatMediaId()) || hasText(article.getWechatPublishId())) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", article.getId() + "-wechat");
            record.put("action", ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) ? "publish" : "draft");
            record.put("status", errorMessage == null ? "SUCCESS" : "FAILED");
            record.put("mediaId", article.getWechatMediaId());
            record.put("publishId", article.getWechatPublishId());
            record.put("url", article.getWechatUrl());
            record.put("errorMessage", errorMessage);
            record.put("createdAt", now);
            records.add(record);
        }
        return new ContentAutomationView(currentStage, logs, jobs, records, buildArtifacts(article), buildQualityGate(article));
    }

    public ContentAutomationView buildAgentAutomation(ContentArticle article, Map<String, Object> topic) {
        String now = LocalDateTime.now().toString();
        String error = article.getErrorMessage();
        boolean localDraft = article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-");
        boolean draftOk = hasText(article.getWechatMediaId()) && !localDraft && !hasText(error);
        String currentStage = resolveAgentStage(article, error, draftOk, localDraft);

        boolean hasEvidence = hasText(article.getEvidenceJson());
        boolean hasPlan = hasText(article.getPlanJson());
        boolean hasReview = hasText(article.getReviewJson());

        List<Object> logs = new ArrayList<>();
        logs.add(logItem(article.getId() + "-agent-topic", "editorial_decision", "SUCCESS",
                "已自动选题：" + defaultText(asString(topic.get("title")), article.getTitle()),
                asString(topic.get("reason")), now));
        logs.add(logItem(article.getId() + "-agent-evidence", "evidence", hasEvidence ? "SUCCESS" : "SKIPPED",
                hasEvidence ? "已完成联网证据补全" : "未启用或未取回联网证据，以观点分析为主", null, now));
        logs.add(logItem(article.getId() + "-agent-plan", "plan", hasPlan ? "SUCCESS" : "SKIPPED",
                hasPlan ? "已生成文章写作大纲" : "未生成结构化大纲，按默认结构写作", null, now));
        logs.add(logItem(article.getId() + "-agent-generate", "generate", "SUCCESS",
                "已自动编写正文并转换为公众号 HTML", null, valueOrNow(article.getCreatedAt(), now)));
        logs.add(logItem(article.getId() + "-agent-review", "review", hasReview ? "SUCCESS" : "SKIPPED",
                hasReview
                        ? "已完成质量审稿" + (article.getQualityScore() == null ? "" : "，评分 " + article.getQualityScore())
                        : "未启用质量审稿",
                null, now));
        logs.add(logItem(article.getId() + "-agent-wechat-draft", "wechat_draft", draftOk ? "SUCCESS" : (hasText(error) || localDraft ? "FAILED" : "PENDING"),
                draftOk ? "已创建微信草稿" : (localDraft ? "微信草稿失败，已保留本地草稿" : "等待创建微信草稿"),
                error, now));

        List<Object> jobs = new ArrayList<>();
        jobs.add(jobItem(article.getId() + "-agent-run", "generate", "SUCCESS", null, now));
        jobs.add(jobItem(article.getId() + "-agent-wechat-draft-job", "wechat_draft", draftOk ? "SUCCESS" : (hasText(error) || localDraft ? "FAILED" : "PENDING"), error, now));

        List<Object> records = new ArrayList<>();
        if (hasText(article.getWechatMediaId())) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", article.getId() + "-agent-wechat");
            record.put("action", "draft");
            record.put("status", draftOk ? "SUCCESS" : "FAILED");
            record.put("mediaId", article.getWechatMediaId());
            record.put("publishId", article.getWechatPublishId());
            record.put("url", article.getWechatUrl());
            record.put("errorMessage", error);
            record.put("createdAt", now);
            records.add(record);
        }
        return new ContentAutomationView(currentStage, logs, jobs, records, buildArtifacts(article), buildQualityGate(article));
    }

    private String resolveAgentStage(ContentArticle article, String error, boolean draftOk, boolean localDraft) {
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus())) {
            return "publish";
        }
        if (hasText(error)) {
            return "quality_gate";
        }
        if (draftOk) {
            return "wechat_draft";
        }
        if (localDraft || ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus())) {
            return "local_draft";
        }
        return "draft_ready";
    }

    public Map<String, Object> logItem(String id, String stage, String status, String message, String detail, String createdAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("stage", stage);
        item.put("status", status);
        item.put("message", message);
        item.put("detail", detail);
        item.put("createdAt", createdAt);
        return item;
    }

    public Map<String, Object> jobItem(String id, String stage, String status, String errorMessage, String now) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("stage", stage);
        item.put("status", status);
        item.put("attempts", 1);
        item.put("maxAttempts", 1);
        item.put("errorMessage", errorMessage);
        item.put("createdAt", now);
        item.put("updatedAt", now);
        return item;
    }


    /**
     * TrendPublish-style artifact manifest.  We keep the heavy payloads on the article row
     * (markdown/html/plan/evidence/review fields) and expose compact descriptors here so the
     * factory console can render a run timeline without loading every large field eagerly.
     */
    private List<Object> buildArtifacts(ContentArticle article) {
        List<Object> artifacts = new ArrayList<>();
        addTextArtifact(artifacts, "markdown", "Markdown Draft", "text/markdown", article.getContentMarkdown());
        addTextArtifact(artifacts, "html", "WeChat HTML", "text/html", article.getContentHtml());
        addTextArtifact(artifacts, "topic-decision", "Topic Decision", "application/json", article.getTopicsJson());
        addTextArtifact(artifacts, "evidence", "Evidence Pack", "application/json", article.getEvidenceJson());
        addTextArtifact(artifacts, "article-plan", "Article Plan", "application/json", article.getPlanJson());
        addTextArtifact(artifacts, "quality-review", "Quality Review", "application/json", article.getReviewJson());
        if (hasText(article.getCoverImageUrl())) {
            Map<String, Object> cover = new LinkedHashMap<>();
            cover.put("key", "cover");
            cover.put("label", "Cover Image");
            cover.put("contentType", "image/url");
            cover.put("status", "READY");
            cover.put("url", article.getCoverImageUrl());
            cover.put("prompt", article.getCoverPrompt());
            artifacts.add(cover);
        } else if (hasText(article.getCoverPrompt())) {
            addTextArtifact(artifacts, "cover-prompt", "Cover Prompt", "text/plain", article.getCoverPrompt());
        }
        return artifacts;
    }

    private void addTextArtifact(List<Object> artifacts, String key, String label, String contentType, String value) {
        if (!hasText(value)) {
            return;
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("key", key);
        artifact.put("label", label);
        artifact.put("contentType", contentType);
        artifact.put("status", "READY");
        artifact.put("size", value.length());
        artifact.put("preview", preview(value));
        artifacts.add(artifact);
    }

    /**
     * Dashboard quality gate: same contract as draft/publish blocking, plus artifact flags.
     */
    private Map<String, Object> buildQualityGate(ContentArticle article) {
        Map<String, Object> gate = new LinkedHashMap<>();
        PublishableArticleContract.Evaluation evaluation = publishableArticleContract.evaluateArticle(article);
        Integer reviewScore = article.getQualityScore();
        int contractScore = evaluation.getScore();
        int minScore = PublishableArticleContract.MIN_PUBLISH_SCORE;
        gate.put("score", reviewScore == null ? contractScore : Math.min(reviewScore, contractScore));
        gate.put("contractScore", contractScore);
        gate.put("reviewScore", reviewScore);
        gate.put("minScore", minScore);
        gate.put("publishable", evaluation.isPublishable() && !evaluation.blocksPublish());
        gate.put("hasEvidence", hasText(article.getEvidenceJson()));
        gate.put("hasPlan", hasText(article.getPlanJson()));
        gate.put("hasReview", hasText(article.getReviewJson()));
        gate.put("revised", hasText(article.getReviewJson()) && article.getReviewJson().contains("\"revised\":true"));
        gate.put("hasCoverImage", hasText(article.getCoverImageUrl()));
        if (evaluation.getMetrics() != null) {
            gate.put("metrics", evaluation.getMetrics());
        }
        if (evaluation.getIssues() != null && !evaluation.getIssues().isEmpty()) {
            gate.put("issues", evaluation.getIssues());
        }
        if (hasText(article.getErrorMessage())) {
            gate.put("decision", "block");
            gate.put("reason", article.getErrorMessage());
        } else {
            gate.put("decision", evaluation.getDecision());
            gate.put("reason", evaluation.getIssues() == null || evaluation.getIssues().isEmpty()
                    ? ("pass".equals(evaluation.getDecision())
                    ? "Deterministic publication contract passed"
                    : "Deterministic publication contract requires revision or regeneration")
                    : evaluation.getIssues().get(0));
        }
        return gate;
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }


    private String valueOrNow(LocalDateTime value, String now) {
        return value == null ? now : value.toString();
    }
}
