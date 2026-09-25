package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptSearchHit {

    private Long transcriptId;
    private Long videoId;
    private String videoTitle;
    private String language;
    private Long segmentId;
    private int position;
    private long startMs;
    private long endMs;
    private String text;
}
