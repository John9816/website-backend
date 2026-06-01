package com.example.website.dto.kb;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KbDocTreeNodeView {
    private Long id;
    private Long parentId;
    private String title;
    private String status;
    private Integer sortOrder;
    private List<KbDocTreeNodeView> children = new ArrayList<>();
}
