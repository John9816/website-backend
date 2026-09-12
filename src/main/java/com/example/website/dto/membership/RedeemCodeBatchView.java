package com.example.website.dto.membership;

import lombok.Data;

import java.util.List;

@Data
public class RedeemCodeBatchView {

    private String type;
    private String typeName;
    private int quantity;
    private List<String> codes;

    public RedeemCodeBatchView(String type, String typeName, List<String> codes) {
        this.type = type;
        this.typeName = typeName;
        this.quantity = codes.size();
        this.codes = codes;
    }
}
