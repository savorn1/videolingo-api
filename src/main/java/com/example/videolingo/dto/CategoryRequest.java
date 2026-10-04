package com.example.videolingo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    // Optional — generated from the name when blank. Normalised to lowercase-with-dashes.
    @Size(max = 120)
    private String slug;

    @Size(max = 500)
    private String description;

    @Pattern(
            regexp =
                    "^(gray|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)?$",
            message = "must be one of the palette colors")
    private String color;

    @Min(-10000)
    @Max(10000)
    private Integer sortOrder;

    // Create only — status has its own endpoint afterwards.
    private Boolean enabled;
}
