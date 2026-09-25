package com.example.videolingo.dto;

import com.example.videolingo.entity.CollectionVisibility;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class CollectionFilterRequest {

    // Title or description.
    private String search;
    private CollectionVisibility visibility;
    private Long ownerId;
    // Collections containing this video.
    private Long videoId;

    private String sortBy = "updatedAt";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 12;
}
