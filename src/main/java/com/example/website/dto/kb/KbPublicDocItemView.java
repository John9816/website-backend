package com.example.website.dto.kb;

import com.example.website.repository.KbDocShareRepository;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbPublicDocItemView {
    private Long id;
    private String token;
    private Long parentId;
    private String title;
    private String summary;
    private Integer sortOrder;
    private LocalDateTime updatedAt;

    public static KbPublicDocItemView from(KbDocShareRepository.PublicDocItem item) {
        KbPublicDocItemView view = new KbPublicDocItemView();
        view.id = item.getDocId();
        view.token = item.getToken();
        view.parentId = item.getParentId();
        view.title = item.getTitle();
        view.summary = item.getSummary();
        view.sortOrder = item.getSortOrder();
        view.updatedAt = item.getUpdatedAt();
        return view;
    }
}
