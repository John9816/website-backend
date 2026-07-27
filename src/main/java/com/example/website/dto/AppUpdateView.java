package com.example.website.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AppUpdateView {
    private String version;
    private Integer buildNumber;
    private String downloadUrl;
    private String description;
    private Boolean forceUpdate;
    private Integer minBuildNumber;
}
