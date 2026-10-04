package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.LanguageResponse;
import com.example.videolingo.service.LanguageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public, read-only language catalog (permitAll in SecurityConfig) — every
// language with its enabled/default flags, for labels and pickers across the
// app. Nothing sensitive, and it's needed before/without module permissions.
@RestController
@RequestMapping("/api/languages")
@RequiredArgsConstructor
public class LanguageCatalogController {

    private final LanguageService languageService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LanguageResponse>>> catalog() {
        // no-cache (revalidate every time), not max-age: after an admin edits a
        // language the app refetches this, and a cached copy would hand back the
        // old list. It's one small query, so freshness wins.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(ApiResponse.success(languageService.catalog()));
    }
}
