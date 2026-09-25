package com.example.videolingo.dto;

import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class TagFilterRequest {

    // Name, slug or description.
    private String search;
    // true = only tags no live video uses (cleanup candidates).
    private Boolean unused;

    private String sortBy = "name";
    private String sortOrder = "asc";
    private int page = 1;
    private int size = 25;
}
