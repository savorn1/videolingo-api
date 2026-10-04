package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.TagResponse;
import com.example.videolingo.service.TagService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Public tag autocomplete (permitAll in SecurityConfig) — up to 20 tags whose
// name contains `q`, prefix matches first. Unlike categories there can be
// thousands of tags, so this searches instead of returning a full catalog.
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagSuggestController {

    private final TagService tagService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TagResponse>>> suggest(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(ApiResponse.success(tagService.suggest(q, limit)));
    }
}
