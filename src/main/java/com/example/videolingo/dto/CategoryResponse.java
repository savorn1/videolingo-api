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
public class CategoryResponse {

    private Long id;
    private String name;
    private String slug;
    private String description;
    private String color;
    private int sortOrder;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Live (non-trashed) videos filed under it. Admin responses only.
    private Long videoCount;
}
