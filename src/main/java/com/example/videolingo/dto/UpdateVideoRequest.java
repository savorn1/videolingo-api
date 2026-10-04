package com.example.videolingo.dto;

import com.example.videolingo.entity.VideoVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

// Admin-editable fields only. Technical metadata (duration, resolution, size,
// format) and the file itself aren't editable — they describe the upload.
// Enabled/deleted/archived/owner have their own endpoints.
@Data
public class UpdateVideoRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 5000)
    private String description;

    @Size(max = 10)
    private String language;

    @Size(max = 1000)
    private String thumbnailUrl;

    // null = leave unchanged.
    private VideoVisibility visibility;

    // Replaces the video's categories. null = leave them unchanged (so older
    // clients that don't send it don't wipe categories); [] = remove all.
    @Size(max = 20)
    private List<Long> categoryIds;
}
