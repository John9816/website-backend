package com.example.website.module.music.api.dto;

import lombok.Data;

@Data
public class MyListenTimeView {

    /** all-time total from heartbeat data (0 when only estimated data exists) */
    private long totalSec;
    private long weekSec;
    private long monthSec;
    /** 0 means not on the leaderboard for that period */
    private int weekRank;
    private int monthRank;
    private long weekParticipants;
    private long monthParticipants;
    private String weekSource;
    private String monthSource;
}
