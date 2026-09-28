package com.example.videolingo.dto;

import lombok.Data;

@Data
public class MoveVideoOwnerRequest {

    // Null clears ownership (an "unowned" video), same as CollectionRequest.ownerId.
    private Long ownerId;
}
