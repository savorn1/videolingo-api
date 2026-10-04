package com.example.videolingo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class AssignTagsRequest {

    // Added to the video's existing tags (already-present ones are ignored).
    @NotEmpty
    @Size(max = 30)
    private List<Long> tagIds;
}
