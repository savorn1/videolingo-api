package com.example.videolingo.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ForceLogoutRequest {

    @NotEmpty
    private List<Long> userIds;
}
