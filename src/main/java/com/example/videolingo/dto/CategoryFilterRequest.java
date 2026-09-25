package com.example.videolingo.dto;

import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class CategoryFilterRequest {

    // Name, slug or description.
    private String search;
    private Boolean enabled;

    private String sortBy = "sortOrder";
    private String sortOrder = "asc";
    private int page = 1;
    private int size = 50;
}
