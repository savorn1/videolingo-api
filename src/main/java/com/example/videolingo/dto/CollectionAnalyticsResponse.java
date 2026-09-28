package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// Completion rollup for a collection: how many learners have touched it, how
// many finished every video in it, and per-video started/completed/progress.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionAnalyticsResponse {

    private long uniqueLearners;
    private long finishedCourse;
    private List<VideoStat> videos;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoStat {
        private Long videoId;
        private String title;
        private long started;
        private long completed;
        // Average of each learner's percent watched; null if nobody's started it.
        private Integer avgPercent;
    }
}
