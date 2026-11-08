package com.example.website.module.membership.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RedeemCodeRequest {

    @NotBlank(message = "兑换码不能为空")
    private String code;
}
