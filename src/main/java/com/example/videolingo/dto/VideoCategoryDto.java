package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// A category as embedded in a video response.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoCategoryDto {

    private Long id;
    private String name;
    private String color;
    private boolean enabled;
}
