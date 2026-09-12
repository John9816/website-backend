package com.example.website.dto.membership;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RedeemCodeRequest {

    @NotBlank(message = "兑换码不能为空")
    private String code;
}
