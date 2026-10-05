package com.example.videolingo.dto;

import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.entity.VideoVisibility;
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
public class VideoResponse {

    private Long id;
    private String title;
    private String description;
    private Long ownerId;
    // Null when the owner account has since been deleted.
    private String ownerUsername;
    private String language;
    private String videoUrl;
    private String storageKey;
    // Original link when the video was imported into our storage.
    private String importedFrom;
    // UPLOAD, YOUTUBE, VIMEO, FACEBOOK or URL (see VideoSource).
    private VideoSource source;
    private String externalId;
    private String sourceAuthor;
    // Platform player to embed; null when the video plays as a file (videoUrl).
    private String embedUrl;
    private String thumbnailUrl;
    private Integer durationSeconds;
    // Exact length when known (null for videos not re-read since it was recorded); durationSeconds is rounded.
    private Long durationMs;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private String mimeType;
    private boolean enabled;
    private boolean archived;
    private LocalDateTime archivedAt;
    private VideoVisibility visibility;
    private boolean deleted;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long viewCount;
    private List<VideoCategoryDto> categories;
    // Alphabetical.
    private List<VideoTagDto> tags;
}
