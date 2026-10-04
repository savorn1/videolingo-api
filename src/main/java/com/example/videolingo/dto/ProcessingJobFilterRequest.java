package com.example.videolingo.dto;

import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import java.time.LocalDate;
import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@ParameterObject
public class ProcessingJobFilterRequest {

    // Matches the job id exactly (when numeric) or the video title, case-insensitively.
    private String search;
    private ProcessingJobStatus status;
    private ProcessingJobType type;
    private Long videoId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdTo;

    private String sortBy = "id";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 10;
}
