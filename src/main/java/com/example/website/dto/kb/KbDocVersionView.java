package com.example.website.dto.kb;

import com.example.website.entity.KbDocVersion;
import com.example.website.repository.KbDocVersionRepository;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbDocVersionView {
    private Long id;
    private Long docId;
    private Integer versionNo;
    private String title;
    private String summary;
    private Long editorUserId;
    private String changeNote;
    private LocalDateTime createdAt;

    public static KbDocVersionView from(KbDocVersionRepository.KbDocVersionSummary s) {
        KbDocVersionView v = new KbDocVersionView();
        v.id = s.getId();
        v.docId = s.getDocId();
        v.versionNo = s.getVersionNo();
        v.title = s.getTitle();
        v.summary = s.getSummary();
        v.editorUserId = s.getEditorUserId();
        v.changeNote = s.getChangeNote();
        v.createdAt = s.getCreatedAt();
        return v;
    }

    public static KbDocVersionView from(KbDocVersion ver) {
        KbDocVersionView v = new KbDocVersionView();
        v.id = ver.getId();
        v.docId = ver.getDocId();
        v.versionNo = ver.getVersionNo();
        v.title = ver.getTitle();
        v.summary = ver.getSummary();
        v.editorUserId = ver.getEditorUserId();
        v.changeNote = ver.getChangeNote();
        v.createdAt = ver.getCreatedAt();
        return v;
    }
}
