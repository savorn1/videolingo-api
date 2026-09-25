package com.example.videolingo.dto;

import lombok.Data;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@ParameterObject
public class VideoFilterRequest {

    // Case-insensitive substring match against title OR description.
    private String search;
    private Long ownerId;
    private String language;
    private Boolean enabled;
    private Long categoryId;
    private Long tagId;
    // false (default) lists live videos; true lists the trash.
    private boolean deleted = false;
    // Inclusive range on createdAt (dates, not datetimes).
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdTo;

    private String sortBy = "id";
    private String sortOrder = "desc";
    private int page = 1;
    private int size = 10;
}
