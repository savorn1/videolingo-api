package com.example.videolingo.service.impl;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.TagFilterRequest;
import com.example.videolingo.dto.TagRequest;
import com.example.videolingo.dto.TagResponse;
import com.example.videolingo.entity.Tag;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.TagRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.TagService;
import com.example.videolingo.util.PageableUtils;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private static final Set<String> SORTABLE = Set.of("id", "name", "slug", "createdAt", "updatedAt");
    static final int MAX_NAME_LENGTH = 50;

    private final TagRepository tagRepository;
    private final VideoRepository videoRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TagResponse> list(TagFilterRequest filter) {
        List<Specification<Tag>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + TranscriptServiceImpl.escapeLike(normalizeName(filter.getSearch()).toLowerCase()) + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (filter.getUnused() != null) {
            // "Used" = on at least one live video, matching videoCount.
            conditions.add((root, query, cb) -> {
                Subquery<Long> used = query.subquery(Long.class);
                var video = used.from(Video.class);
                var tagId = video.join("tagIds");
                used.select(tagId.as(Long.class)).where(cb.isNull(video.get("deletedAt")));
                return filter.getUnused() ? cb.not(root.get("id").in(used)) : root.get("id").in(used);
            });
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "name";
        Pageable pageable = PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder());
        if (sortBy.equals("name")) {
            // Tags are typed in any case, and a plain ORDER BY name puts every
            // capitalised tag before every lowercase one ("JLPT N5" < "beginner").
            // Pageable's Sort can't say lower(name), so order inside the query —
            // but not in the count query, where Postgres rejects an ORDER BY.
            boolean asc = !"desc".equalsIgnoreCase(filter.getSortOrder());
            conditions.add((root, query, cb) -> {
                if (!Long.class.equals(query.getResultType())) {
                    var key = cb.lower(root.get("name"));
                    query.orderBy(asc ? cb.asc(key) : cb.desc(key), cb.asc(root.get("id")));
                }
                return cb.conjunction();
            });
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        }
        Page<Tag> page = tagRepository.findAll(Specification.allOf(conditions), pageable);
        Map<Long, Long> counts = countVideos(page.getContent().stream().map(Tag::getId).toList());
        return PageResponse.of(page.map(t -> toResponse(t, counts.getOrDefault(t.getId(), 0L))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagResponse> suggest(String query, int limit) {
        String q = query == null ? "" : normalizeName(query).toLowerCase();
        int size = Math.max(1, Math.min(limit, 20));
        return tagRepository.suggest(TranscriptServiceImpl.escapeLike(q), PageRequest.of(0, size)).stream()
                .map(t -> toResponse(t, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TagResponse get(Long id) {
        Tag tag = find(id);
        return toResponse(tag, countVideos(List.of(id)).getOrDefault(id, 0L));
    }

    @Override
    @Transactional
    public TagResponse create(TagRequest request) {
        String name = requireName(request.getName());
        if (tagRepository.existsByNameIgnoreCase(name)) {
            throw new AppException(HttpStatus.CONFLICT, "The tag \"" + name + "\" already exists");
        }
        Tag tag = tagRepository.save(Tag.builder()
                .name(name)
                .slug(chooseSlug(request.getSlug(), name, null))
                .description(blankToNull(request.getDescription()))
                .build());
        return toResponse(tag, 0L);
    }

    @Override
    @Transactional
    public TagResponse update(Long id, TagRequest request) {
        Tag tag = find(id);
        String name = requireName(request.getName());
        if (tagRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new AppException(HttpStatus.CONFLICT, "The tag \"" + name + "\" already exists");
        }
        tag.setName(name);
        if (request.getSlug() != null && !request.getSlug().isBlank()) {
            tag.setSlug(chooseSlug(request.getSlug(), name, id));
        }
        tag.setDescription(blankToNull(request.getDescription()));
        tagRepository.save(tag);
        return get(id);
    }

    @Override
    @Transactional
    public int delete(Long id) {
        Tag tag = find(id);
        int detached = videoRepository.detachTag(id);
        tagRepository.delete(tag);
        return detached;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * How a tag name is stored: surrounding whitespace and leading '#'s
     * dropped ("#slang" → "slang"), inner whitespace collapsed to one space.
     * Case is kept as typed; uniqueness ignores case.
     */
    static String normalizeName(String raw) {
        return raw.strip().replaceFirst("^#+\\s*", "").replaceAll("\\s+", " ").strip();
    }

    private static String requireName(String raw) {
        String name = normalizeName(raw);
        if (name.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tag name can't be empty");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Tag names can be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private Tag find(Long id) {
        return tagRepository.findById(id).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Tag not found with id: " + id));
    }

    private String chooseSlug(String requested, String name, Long selfId) {
        if (requested != null && !requested.isBlank()) {
            String slug = TranscriptServiceImpl.slug(requested);
            boolean taken = selfId == null ? tagRepository.existsBySlug(slug) : tagRepository.existsBySlugAndIdNot(slug, selfId);
            if (taken) {
                throw new AppException(HttpStatus.CONFLICT, "The slug \"" + slug + "\" is already used by another tag");
            }
            return slug;
        }
        String base = TranscriptServiceImpl.slug(name);
        String slug = base;
        for (int n = 2; tagRepository.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private Map<Long, Long> countVideos(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return videoRepository.countLiveByTagIds(ids).stream()
                .collect(Collectors.toMap(VideoRepository.TagUsage::getTagId, VideoRepository.TagUsage::getCount));
    }

    private static TagResponse toResponse(Tag t, Long videoCount) {
        return TagResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .slug(t.getSlug())
                .description(t.getDescription())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .videoCount(videoCount)
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
