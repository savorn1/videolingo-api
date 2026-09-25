package com.example.videolingo.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class PageResponse<T> {

    private String traceId;
    private int statusCode;
    private String message;
    private List<T> data;
    private Metadata metadata;

    public PageResponse() {
        this.traceId = UUID.randomUUID().toString();
    }

    public PageResponse(Page<T> pageData) {
        this.traceId = UUID.randomUUID().toString();
        this.statusCode = 200;
        this.message = "success";
        this.data = pageData.getContent();
        this.metadata = new Metadata(pageData);
    }

    public static <T> PageResponse<T> of(Page<T> pageData) {
        return new PageResponse<>(pageData);
    }

    @Getter
    @Setter
    public static class Metadata {
        private boolean hasNext;
        private boolean hasPrev;
        private int totalPage;
        private int currentPage;
        private int limit;
        private long totalCount;

        public Metadata() {}

        public Metadata(Page<?> page) {
            this.hasNext = page.hasNext();
            this.hasPrev = page.hasPrevious();
            this.totalPage = page.getTotalPages();
            this.currentPage = page.getNumber() + 1;
            this.limit = page.getSize();
            this.totalCount = page.getTotalElements();
        }
    }
}
