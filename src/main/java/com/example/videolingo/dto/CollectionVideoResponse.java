package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// A video as it sits in a collection.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionVideoResponse {

    private int position;
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
