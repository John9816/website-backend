package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_automation_task", indexes = {
        @Index(name = "idx_user_active", columnList = "user_id,is_active"),
        @Index(name = "idx_next_run", columnList = "next_run_at,is_active"),
        @Index(name = "idx_status", columnList = "status,updated_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ContentAutomationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_name", nullable = false, length = 200)
    private String taskName;

    @Column(name = "task_type", nullable = false, length = 40)
    private String taskType;

    @Column(name = "schedule_type", nullable = false, length = 40)
    private String scheduleType;

    @Column(name = "schedule_expression", length = 200)
    private String scheduleExpression;

    @Column(name = "template_id")
    private Long templateId;

    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false, length = 40)
    private String status = "PENDING";

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "run_started_at")
    private LocalDateTime runStartedAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "run_count")
    private Integer runCount = 0;

    @Column(name = "success_count")
    private Integer successCount = 0;

    @Column(name = "fail_count")
    private Integer failCount = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
