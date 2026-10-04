package com.example.videolingo.service;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.TagFilterRequest;
import com.example.videolingo.dto.TagRequest;
import com.example.videolingo.dto.TagResponse;
import java.util.List;

public interface TagService {

    PageResponse<TagResponse> list(TagFilterRequest filter);

    // Autocomplete for the tag input (public): at most `limit` matches.
    List<TagResponse> suggest(String query, int limit);

    TagResponse get(Long id);

    TagResponse create(TagRequest request);

    TagResponse update(Long id, TagRequest request);

    // Deletes the tag and removes it from every video. Returns how many videos lost it.
    int delete(Long id);
}
