package com.example.website.service.content;

import com.example.website.dto.content.ContentAutomationView;
import com.example.website.entity.ContentArticle;
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
 * <p>Extracted verbatim from {@code ContentArticleService}. Stateless; safe to share as a
 * singleton bean.
 */
@Component
public class ContentAutomationBuilder {

    public ContentAutomationView buildAutomation(ContentArticle article, String errorMessage) {
        return buildAutomation(article, errorMessage, article.getStatus().equals(ContentArticle.STATUS_PUBLISHED) ? "publish" : "wechat_draft");
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
        return new ContentAutomationView(currentStage, logs, jobs, records);
    }

    public ContentAutomationView buildAgentAutomation(ContentArticle article, Map<String, Object> topic) {
        String now = LocalDateTime.now().toString();
        String error = article.getErrorMessage();
        boolean localDraft = article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-");
        boolean draftOk = hasText(article.getWechatMediaId()) && !localDraft && !hasText(error);

        List<Object> logs = new ArrayList<>();
        logs.add(logItem(article.getId() + "-agent-topic", "topic", "SUCCESS",
                "已自动选题：" + defaultText(asString(topic.get("title")), article.getTitle()),
                asString(topic.get("reason")), now));
        logs.add(logItem(article.getId() + "-agent-research", "research", "SUCCESS",
                "已生成选题角度、读者画像和写作方向", asString(topic.get("summary")), now));
        logs.add(logItem(article.getId() + "-agent-generate", "generate", "SUCCESS",
                "已自动编写正文并转换为公众号 HTML", null, valueOrNow(article.getCreatedAt(), now)));
        logs.add(logItem(article.getId() + "-agent-review", "review", article.getRiskTipsJson() == null ? "SKIPPED" : "SUCCESS",
                "已生成发布前复核提示", null, now));
        logs.add(logItem(article.getId() + "-agent-wechat-draft", "wechat_draft", draftOk ? "SUCCESS" : (localDraft ? "FAILED" : "PENDING"),
                draftOk ? "已创建微信草稿" : (localDraft ? "微信草稿失败，已保留本地草稿" : "等待创建微信草稿"),
                error, now));

        List<Object> jobs = new ArrayList<>();
        jobs.add(jobItem(article.getId() + "-agent-run", "generate", "SUCCESS", null, now));
        jobs.add(jobItem(article.getId() + "-agent-wechat-draft-job", "wechat_draft", draftOk ? "SUCCESS" : (localDraft ? "FAILED" : "PENDING"), error, now));

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
        return new ContentAutomationView("wechat_draft", logs, jobs, records);
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

    private String valueOrNow(LocalDateTime value, String now) {
        return value == null ? now : value.toString();
    }
}
