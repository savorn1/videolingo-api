package com.example.videolingo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AddCollectionVideosRequest {

    // Appended in this order; videos already in the collection are skipped.
    @NotEmpty @Size(max = 500)
    private List<Long> videoIds;
}
