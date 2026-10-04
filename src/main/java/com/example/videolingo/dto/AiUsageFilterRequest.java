package com.example.videolingo.dto;

import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageStatus;
import java.time.LocalDate;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@ParameterObject
public class AiUsageFilterRequest {

    private AiFeature feature;
    private AiUsageStatus status;
    private String model;
    private Long videoId;
    private String username;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private String sortBy = "createdAt";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 20;
}
