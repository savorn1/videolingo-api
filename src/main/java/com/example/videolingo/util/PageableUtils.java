package com.example.videolingo.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

// Shared page/size/sortBy/sortOrder -> Pageable conversion, used by every
// filter request so the "1-based page number, default-desc sort" convention
// stays in one place.
public final class PageableUtils {

    private PageableUtils() {
    }

    public static Pageable of(int page, int size, String sortBy, String sortOrder) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Empty values always sort last, in either direction — Postgres would
        // otherwise put NULLs first on DESC, so "newest last login" would open
        // with every account that has never signed in.
        Sort sort = Sort.by(new Sort.Order(direction, sortBy).nullsLast());
        return PageRequest.of(Math.max(page - 1, 0), size, sort);
    }
}
