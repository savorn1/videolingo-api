package com.example.videolingo.dto;

import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class LanguageFilterRequest {

    // Matches code, name or native name, case-insensitively.
    private String search;
    private Boolean enabled;

    private String sortBy = "name";
    private String sortOrder = "asc";
    private int page = 1;
    private int size = 50;
}
