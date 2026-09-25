package com.example.videolingo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateVideoStatusRequest {

    @NotNull
    private Boolean enabled;
}
