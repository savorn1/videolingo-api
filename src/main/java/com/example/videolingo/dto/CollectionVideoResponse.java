package com.example.videolingo.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// A video as it sits in a collection.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionVideoResponse {

    private int position;
    // Grouping label ("Week 1"); null if this video isn't in a section.
    private String section;
    private Long videoId;
    // Null only if the video row itself is gone.
    private String title;
    private String thumbnailUrl;
    private Integer durationSeconds;
    private String language;
    private boolean enabled;
    private boolean deleted;
    private String addedBy;
    private LocalDateTime addedAt;
}
