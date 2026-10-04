package com.example.videolingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagRequest {

    // Normalised before saving: leading '#' dropped, whitespace collapsed.
    @NotBlank
    @Size(max = 60)
    private String name;

    // Optional — generated from the name when blank (create), kept when blank (update).
    @Size(max = 60)
    private String slug;

    @Size(max = 300)
    private String description;
}
