package com.example.videolingo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LanguageResponse {

    private Long id;
    private String code;
    private String name;
    private String nativeName;
    private boolean enabled;
    // Boxed on purpose: Lombok then generates getIsDefault(), which Jackson
    // serialises as "isDefault" (a primitive's isDefault() would become "default").
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Admin responses only (null on the public catalog).
    private Long videoCount;
    private Long transcriptCount;
    // Why it can't be deleted / disabled, or null if it can — computed here so
    // the UI never duplicates the rules.
    private String deleteBlockedReason;
    private String disableBlockedReason;
}
