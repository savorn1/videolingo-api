package com.example.videolingo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateSectionsRequest {

    // videoId -> section label; must already be in the collection. A blank/null label clears it.
    @NotNull
    private Map<Long, String> sections;
}
