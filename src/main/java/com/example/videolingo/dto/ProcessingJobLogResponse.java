package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingJobLogResponse {

    private Long id;
    private String level;
    private String message;
    private LocalDateTime createdAt;
}
