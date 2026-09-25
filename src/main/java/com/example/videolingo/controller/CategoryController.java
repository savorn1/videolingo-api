package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.CategoryFilterRequest;
import com.example.videolingo.dto.CategoryRequest;
import com.example.videolingo.dto.CategoryResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.UpdateStatusRequest;
import com.example.videolingo.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// Admin category management, gated as module "categories" (GET = READ, the
// rest WRITE). The read-only catalog for pickers is GET /api/categories.
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<PageResponse<CategoryResponse>> list(@ModelAttribute CategoryFilterRequest filter) {
        return ResponseEntity.ok(categoryService.list(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Category created", categoryService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Category updated", categoryService.update(id, request)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(request.getEnabled() ? "Category enabled" : "Category disabled",
                categoryService.setEnabled(id, request.getEnabled())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Integer>> delete(@PathVariable Long id) {
        int detached = categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(detached == 0 ? "Category deleted"
                : "Category deleted and removed from " + detached + (detached == 1 ? " video" : " videos"), detached));
    }
}
