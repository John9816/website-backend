package com.example.website.dto.kb;

import com.example.website.repository.KbDocRepository;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbDocSummaryView {
    private Long id;
    private Long spaceId;
    private Long parentId;
    private String title;
    private String summary;
    private String status;
    private Integer sortOrder;
    private Integer versionNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KbDocSummaryView from(KbDocRepository.KbDocSummary s) {
        KbDocSummaryView v = new KbDocSummaryView();
        v.id = s.getId();
        v.spaceId = s.getSpaceId();
        v.parentId = s.getParentId();
        v.title = s.getTitle();
        v.summary = s.getSummary();
        v.status = s.getStatus();
        v.sortOrder = s.getSortOrder();
        v.versionNo = s.getVersionNo();
        v.createdAt = s.getCreatedAt();
        v.updatedAt = s.getUpdatedAt();
        return v;
    }
}
