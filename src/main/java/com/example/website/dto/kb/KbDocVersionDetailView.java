package com.example.website.dto.kb;

import com.example.website.entity.KbDocVersion;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbDocVersionDetailView {
    private Long id;
    private Long docId;
    private Integer versionNo;
    private String title;
    private String summary;
    private String contentJson;
    private String contentHtml;
    private Long editorUserId;
    private String changeNote;
    private LocalDateTime createdAt;

    public static KbDocVersionDetailView from(KbDocVersion ver) {
        KbDocVersionDetailView v = new KbDocVersionDetailView();
        v.id = ver.getId();
        v.docId = ver.getDocId();
        v.versionNo = ver.getVersionNo();
        v.title = ver.getTitle();
        v.summary = ver.getSummary();
        v.contentJson = ver.getContentJson();
        v.contentHtml = ver.getContentHtml();
        v.editorUserId = ver.getEditorUserId();
        v.changeNote = ver.getChangeNote();
        v.createdAt = ver.getCreatedAt();
        return v;
    }
}
