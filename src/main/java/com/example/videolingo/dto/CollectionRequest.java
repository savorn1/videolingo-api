package com.example.videolingo.dto;

import com.example.videolingo.entity.CollectionVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class CollectionRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    // Optional — generated from the title when blank (create), kept when blank (update).
    @Size(max = 120)
    private String slug;

    @Size(max = 2000)
    private String description;

    @Size(max = 1000)
    private String coverUrl;

    @NotNull
    private CollectionVisibility visibility;

    // Defaults to the creating admin. Must be an existing user.
    private Long ownerId;

    // Create only: initial videos, in order. Ignored on update (use the /videos endpoints).
    @Size(max = 500)
    private List<Long> videoIds;
}
