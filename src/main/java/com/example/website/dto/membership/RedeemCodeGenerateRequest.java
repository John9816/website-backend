package com.example.website.dto.membership;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
public class RedeemCodeGenerateRequest {

    @NotBlank(message = "会员类型不能为空")
    private String type;

    @Min(value = 1, message = "生成数量至少为 1")
    @Max(value = 100, message = "单次最多生成 100 个兑换码")
    private int quantity = 1;
}
