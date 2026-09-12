package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class UserCreditView {
    private int credits;
    private int imageCreditCost;
    private int dailyCheckInReward;
    private boolean checkedInToday;
    private LocalDate lastCheckInDate;
}
