package com.example.videolingo.dto;

import com.example.videolingo.entity.ReviewStatus;
import com.example.videolingo.entity.SubtitleSource;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class SubtitleFilterRequest {

    // Video title or track label.
    private String search;
    private Long videoId;
    // Several videos at once (e.g. one page of the library grid), comma-separated in the query.
    private java.util.List<Long> videoIds;
    private String language;
    private SubtitleSource source;
    private Boolean published;
    private ReviewStatus reviewStatus;
    // true = only tracks with readability warnings.
    private Boolean hasIssues;

    private String sortBy = "updatedAt";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 10;
}
