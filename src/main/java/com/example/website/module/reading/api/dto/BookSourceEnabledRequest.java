package com.example.website.module.reading.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
public class BookSourceEnabledRequest {
    @NotNull
    private Boolean enabled;
}
