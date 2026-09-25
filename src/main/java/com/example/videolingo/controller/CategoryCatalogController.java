package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CategoryResponse;
import com.example.videolingo.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Public, read-only category catalog (permitAll in SecurityConfig): every
// category with its enabled flag, for pickers and learner browsing. no-cache
// for the same reason as the language catalog — admins edit it.
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryCatalogController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(ApiResponse.success(categoryService.catalog()));
    }
}
