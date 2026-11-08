package com.example.website.module.content.application;

import com.example.website.common.BusinessException;
import com.example.website.module.content.api.dto.ContentAgentRunJobView;
import com.example.website.module.content.api.dto.ContentAgentRunRequest;
import com.example.website.module.content.api.dto.ContentAgentRunResult;
import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;
import com.example.website.module.content.api.dto.ContentArticleView;
import com.example.website.module.content.api.dto.ContentAutomationView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ContentAgentJobService {

    private static final int MAX_JOBS = 200;

    private final ContentArticleService contentArticleService;
    private final Map<String, ContentAgentRunJobView> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> jobOwners = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "content-agent-job-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }, new ThreadPoolExecutor.AbortPolicy());

    public ContentAgentRunJobView start(Long userId, ContentAgentRunRequest request) {
        return startAgent(userId, request);
    }

    public ContentAgentRunJobView startAgent(Long userId, ContentAgentRunRequest request) {
        final String jobId = newJobId("content-agent-");
        ContentAgentRunJobView job = createPendingJob(jobId);
        jobs.put(jobId, job);
        jobOwners.put(jobId, userId);
        submit(jobId, () -> runAgentJob(userId, request, jobId));
        return copy(job);
    }

    public ContentAgentRunJobView startGenerate(Long userId, ContentArticleGenerateRequest request) {
        final String jobId = newJobId("content-generate-");
        ContentAgentRunJobView job = createPendingJob(jobId);
        jobs.put(jobId, job);
        jobOwners.put(jobId, userId);
        submit(jobId, () -> runGenerateJob(userId, request, jobId));
        return copy(job);
    }

    public ContentAgentRunJobView get(Long userId, String jobId) {
        if (userId == null || jobId == null || !userId.equals(jobOwners.get(jobId))) {
            return null;
        }
        ContentAgentRunJobView job = jobs.get(jobId);
        return job == null ? null : copy(job);
    }

    private void runAgentJob(Long userId, ContentAgentRunRequest request, String jobId) {
        ContentAgentRunJobView job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        updateStage(job, ContentAgentRunJobView.STATUS_RUNNING,
                ContentAgentRunJobView.STAGE_TOPIC, "自动选题中…");
        try {
            ContentAgentRunResult result = contentArticleService.runAgent(userId, request, progressOf(job));
            job.setArticle(result.getArticle());
            job.setAutomation(result.getAutomation());
            job.setTopic(result.getTopic());
            job.setDraft(result.getDraft());
            requireSuccessfulArticle(result.getArticle());
            job.setStatus(ContentAgentRunJobView.STATUS_SUCCESS);
            job.setStage(ContentAgentRunJobView.STAGE_DONE);
            job.setStageLabel("草稿已就绪");
            job.setErrorMessage(null);
        } catch (Exception e) {
            job.setStatus(ContentAgentRunJobView.STATUS_FAILED);
            job.setStage(ContentAgentRunJobView.STAGE_FAILED);
            job.setStageLabel("失败");
            job.setErrorMessage(safeMessage(e));
        } finally {
            job.setUpdatedAt(LocalDateTime.now());
        }
    }

    private void runGenerateJob(Long userId, ContentArticleGenerateRequest request, String jobId) {
        ContentAgentRunJobView job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        updateStage(job, ContentAgentRunJobView.STATUS_RUNNING,
                ContentAgentRunJobView.STAGE_RESEARCH, "开始生成…");
        try {
            ContentArticleView article = contentArticleService.generate(userId, request, progressOf(job));
            ContentAutomationView automation = contentArticleService.automation(userId, article.getId());
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("title", article.getTitle());
            topic.put("source", "manual_generate");
            job.setArticle(article);
            job.setAutomation(automation);
            job.setTopic(topic);
            job.setDraft(null);
            requireSuccessfulArticle(article);
            job.setStatus(ContentAgentRunJobView.STATUS_SUCCESS);
            job.setStage(ContentAgentRunJobView.STAGE_DONE);
            job.setStageLabel(article.getErrorMessage() == null ? "草稿已就绪" : "草稿已生成，待改稿后推微信");
            job.setErrorMessage(null);
        } catch (Exception e) {
            job.setStatus(ContentAgentRunJobView.STATUS_FAILED);
            job.setStage(ContentAgentRunJobView.STAGE_FAILED);
            job.setStageLabel("失败");
            job.setErrorMessage(safeMessage(e));
        } finally {
            job.setUpdatedAt(LocalDateTime.now());
        }
    }

    private ContentArticleService.GenerateProgress progressOf(final ContentAgentRunJobView job) {
        return new ContentArticleService.GenerateProgress() {
            @Override
            public void stage(String stage, String label) {
                updateStage(job, ContentAgentRunJobView.STATUS_RUNNING, stage, label);
            }
        };
    }

    private void updateStage(ContentAgentRunJobView job, String status, String stage, String label) {
        job.setStatus(status);
        job.setStage(stage);
        job.setStageLabel(label);
        job.setUpdatedAt(LocalDateTime.now());
    }

    private ContentAgentRunJobView createPendingJob(String jobId) {
        LocalDateTime now = LocalDateTime.now();
        return new ContentAgentRunJobView(
                jobId,
                ContentAgentRunJobView.STATUS_PENDING,
                ContentAgentRunJobView.STAGE_QUEUED,
                "排队中…",
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    private void submit(String jobId, Runnable work) {
        try {
            executor.submit(work);
        } catch (RejectedExecutionException error) {
            jobs.remove(jobId);
            jobOwners.remove(jobId);
            throw new BusinessException(429, "Content automation queue is full; retry later");
        }
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

    private String newJobId(String prefix) {
        evictOldJobsIfNeeded();
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String safeMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e == null ? "Unknown error" : e.getClass().getSimpleName();
        }
        String text = message.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1***");
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private void evictOldJobsIfNeeded() {
        if (jobs.size() < MAX_JOBS) {
            return;
        }
        String oldestKey = null;
        LocalDateTime oldest = null;
        for (Map.Entry<String, ContentAgentRunJobView> entry : jobs.entrySet()) {
            LocalDateTime createdAt = entry.getValue().getCreatedAt();
            if (oldest == null || (createdAt != null && createdAt.isBefore(oldest))) {
                oldest = createdAt;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            jobs.remove(oldestKey);
            jobOwners.remove(oldestKey);
        }
    }

    private ContentAgentRunJobView copy(ContentAgentRunJobView job) {
        return new ContentAgentRunJobView(
                job.getJobId(),
                job.getStatus(),
                job.getStage(),
                job.getStageLabel(),
                job.getErrorMessage(),
                job.getArticle(),
                job.getAutomation(),
                cloneMap(job.getTopic()),
                cloneMap(job.getDraft()),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }

    private Map<String, Object> cloneMap(Map<String, Object> value) {
        return value == null ? null : new LinkedHashMap<>(value);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
