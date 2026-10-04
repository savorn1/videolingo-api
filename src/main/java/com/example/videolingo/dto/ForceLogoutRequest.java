package com.example.videolingo.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class ForceLogoutRequest {

    @NotEmpty
    private List<Long> userIds;
}
