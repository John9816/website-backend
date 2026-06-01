package com.example.website.dto.kb;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class KbDocCreateRequest {

    @NotNull(message = "spaceId is required")
    private Long spaceId;

    private Long parentId;

    @NotBlank(message = "title is required")
    @Size(max = 200)
    private String title;

    @Size(max = 500)
    private String summary;

    private String contentJson;

    private String contentHtml;

    @Size(max = 20)
    private String status;

    private Integer sortOrder;
}
