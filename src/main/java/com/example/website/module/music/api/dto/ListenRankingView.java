package com.example.website.module.music.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ListenRankingView {

    /** week | month */
    private String period;
    /** inclusive, ISO yyyy-MM-dd */
    private String from;
    /** exclusive, ISO yyyy-MM-dd */
    private String to;
    /** reported = real heartbeat data; estimated = fallback from play history */
    private String source;
    /** number of users with listening time in this period */
    private long participants;
    private List<ListenRankItemView> items;
}
