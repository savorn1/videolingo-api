package com.example.videolingo.dto;

import com.example.videolingo.entity.TranscriptSource;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class TranscriptFilterRequest {

    // Matches the video title, case-insensitively. (Full-text search over the
    // transcript text is its own endpoint: GET /search.)
    private String search;
    private Long videoId;
    private String language;
    private TranscriptSource source;

    private String sortBy = "updatedAt";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 10;
}
