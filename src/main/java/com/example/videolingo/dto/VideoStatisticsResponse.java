package com.example.videolingo.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoStatisticsResponse {

    private long totalViews;
    // Signed-in viewers only; anonymous views count toward totalViews but not here.
    private long uniqueViewers;
    private long totalWatchSeconds;
    private double averageWatchSeconds;
    // 0–100. Share of views that reached the end.
    private double completionRate;
    // 0–100. Average watched seconds as a share of the video's duration;
    // null when the duration isn't known.
    private Double averagePercentWatched;
    private LocalDateTime lastViewedAt;
    // One entry per day for the last `days` days, oldest first, zero-filled.
    private int days;
    private List<DailyViewCount> dailyViews;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyViewCount {
        private LocalDate date;
        private long views;
    }
}
