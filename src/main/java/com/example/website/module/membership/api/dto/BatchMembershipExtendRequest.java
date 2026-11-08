package com.example.website.module.membership.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

import java.util.List;

@Data
public class BatchMembershipExtendRequest {

    @NotEmpty(message = "用户ID列表不能为空")
    private List<Long> userIds;

    @NotBlank(message = "会员类型不能为空")
    private String type;

}
