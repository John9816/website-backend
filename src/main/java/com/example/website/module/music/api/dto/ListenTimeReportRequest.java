package com.example.website.module.music.api.dto;

import lombok.Data;

/** Heartbeat payload: real seconds played since the previous report. */
@Data
public class ListenTimeReportRequest {

    private Long seconds;
}
