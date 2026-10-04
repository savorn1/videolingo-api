package com.example.videolingo.dto;

import com.example.videolingo.entity.CollectionVisibility;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionResponse {

    private Long id;
    private String title;
    private String slug;
    private String description;
    private String coverUrl;
    private Long ownerId;
    // Null if the owner account was deleted.
    private String ownerUsername;
    private CollectionVisibility visibility;
    private int videoCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Detail only: total length of its live videos, and how many of its
    // videos learners can't currently watch.
    private Long totalDurationSeconds;
    private Long trashedVideoCount;
    private Long disabledVideoCount;
}
