package com.example.website.module.images.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageModelView {

    private String id;
    private String object;
    private Long created;
    private String ownedBy;
}
