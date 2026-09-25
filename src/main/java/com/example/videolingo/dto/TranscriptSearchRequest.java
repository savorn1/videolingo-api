package com.example.videolingo.dto;

import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;

@Data
@ParameterObject
public class TranscriptSearchRequest {

    private String q;
    private Long videoId;
    private String language;
    private int page = 1;
    private int size = 20;
}
