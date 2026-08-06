package com.example.website.dto.book;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
public class BookSourceEnabledRequest {
    @NotNull
    private Boolean enabled;
}
