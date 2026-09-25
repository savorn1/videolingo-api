package com.example.videolingo.service;

import com.example.videolingo.dto.CollectionFilterRequest;
import com.example.videolingo.dto.CollectionRequest;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.CollectionVideoResponse;
import com.example.videolingo.dto.PageResponse;

import java.util.List;

public interface CollectionService {

    PageResponse<CollectionResponse> list(CollectionFilterRequest filter);

    CollectionResponse get(Long id);

    CollectionResponse create(CollectionRequest request, String actingUsername);

    CollectionResponse update(Long id, CollectionRequest request);

    // Deletes the collection and its items; the videos themselves are untouched.
    void delete(Long id);

    PageResponse<CollectionVideoResponse> videos(Long id, int page, int size);

    // Appends videos (skipping ones already present). Returns how many were added.
    int addVideos(Long id, List<Long> videoIds, String actingUsername);

    CollectionResponse removeVideo(Long id, Long videoId);

    // Puts the videos in this order; must list exactly the collection's videos.
    void reorder(Long id, List<Long> videoIds);
}
