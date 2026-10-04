package com.example.videolingo.service;

import com.example.videolingo.dto.CategoryFilterRequest;
import com.example.videolingo.dto.CategoryRequest;
import com.example.videolingo.dto.CategoryResponse;
import com.example.videolingo.dto.PageResponse;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface CategoryService {

    PageResponse<CategoryResponse> list(CategoryFilterRequest filter);

    // Every category, ordered for display, without counts — the public catalog.
    List<CategoryResponse> catalog();

    CategoryResponse get(Long id);

    CategoryResponse create(CategoryRequest request);

    CategoryResponse update(Long id, CategoryRequest request);

    CategoryResponse setEnabled(Long id, boolean enabled);

    // Deletes the category and removes it from every video. Returns how many videos lost it.
    int delete(Long id);

    /**
     * Validates a video's requested category ids: all must exist, and any
     * newly added one must be enabled (ones the video already had may stay
     * even if since disabled). Returns them de-duplicated, in request order.
     */
    Set<Long> resolveForVideo(Collection<Long> requested, Set<Long> current);
}
