package com.example.website.dto.kb;

import com.example.website.entity.KbDoc;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class KbPublicDocView {
    private Long id;
    private String token;
    private String title;
    private String summary;
    private String contentJson;
    private String contentHtml;
    private LocalDateTime updatedAt;
    private List<KbPublicDocItemView> documents;

    public static KbPublicDocView from(KbDoc d, String token, List<KbPublicDocItemView> documents) {
        KbPublicDocView v = new KbPublicDocView();
        v.id = d.getId();
        v.token = token;
        v.title = d.getTitle();
        v.summary = d.getSummary();
        v.contentJson = d.getContentJson();
        v.contentHtml = d.getContentHtml();
        v.updatedAt = d.getUpdatedAt();
        v.documents = documents;
        return v;
    }
}
