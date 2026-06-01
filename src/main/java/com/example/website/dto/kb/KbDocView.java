package com.example.website.dto.kb;

import com.example.website.entity.KbDoc;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class KbDocView {
    private Long id;
    private Long spaceId;
    private Long parentId;
    private String title;
    private String summary;
    private String contentJson;
    private String contentHtml;
    private String status;
    private Integer sortOrder;
    private Integer versionNo;
    private List<KbTagView> tags;
    private KbDocShareView share;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KbDocView from(KbDoc d, List<KbTagView> tags, KbDocShareView share) {
        KbDocView v = new KbDocView();
        v.id = d.getId();
        v.spaceId = d.getSpaceId();
        v.parentId = d.getParentId();
        v.title = d.getTitle();
        v.summary = d.getSummary();
        v.contentJson = d.getContentJson();
        v.contentHtml = d.getContentHtml();
        v.status = d.getStatus();
        v.sortOrder = d.getSortOrder();
        v.versionNo = d.getVersionNo();
        v.tags = tags;
        v.share = share;
        v.createdAt = d.getCreatedAt();
        v.updatedAt = d.getUpdatedAt();
        return v;
    }
}
