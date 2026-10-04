package com.example.videolingo.service.impl;

import com.example.videolingo.dto.CategoryFilterRequest;
import com.example.videolingo.dto.CategoryRequest;
import com.example.videolingo.dto.CategoryResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.Category;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.CategoryRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.CategoryService;
import com.example.videolingo.util.PageableUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final Set<String> SORTABLE =
            Set.of("id", "name", "slug", "sortOrder", "enabled", "createdAt", "updatedAt");

    private final CategoryRepository categoryRepository;
    private final VideoRepository videoRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(CategoryFilterRequest filter) {
        List<Specification<Category>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%"
                    + TranscriptServiceImpl.escapeLike(filter.getSearch().trim().toLowerCase()) + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (filter.getEnabled() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("enabled"), filter.getEnabled()));
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "sortOrder";
        Page<Category> page = categoryRepository.findAll(
                Specification.allOf(conditions),
                PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder()));
        Map<Long, Long> counts =
                countVideos(page.getContent().stream().map(Category::getId).toList());
        return PageResponse.of(page.map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> catalog() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> toResponse(c, null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse get(Long id) {
        Category c = find(id);
        return toResponse(c, countVideos(List.of(id)).getOrDefault(id, 0L));
    }

    @Override
    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String name = request.getName().strip();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new AppException(HttpStatus.CONFLICT, "A category named \"" + name + "\" already exists");
        }
        Category category = Category.builder()
                .name(name)
                .slug(chooseSlug(request.getSlug(), name, null))
                .description(blankToNull(request.getDescription()))
                .color(blankToNull(request.getColor()))
                .sortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build();
        return toResponse(categoryRepository.save(category), 0L);
    }

    @Override
    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = find(id);
        String name = request.getName().strip();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new AppException(HttpStatus.CONFLICT, "A category named \"" + name + "\" already exists");
        }
        category.setName(name);
        // Blank slug on update keeps the current one — renaming shouldn't
        // silently change URLs learners may have bookmarked.
        if (request.getSlug() != null && !request.getSlug().isBlank()) {
            category.setSlug(chooseSlug(request.getSlug(), name, id));
        }
        category.setDescription(blankToNull(request.getDescription()));
        category.setColor(blankToNull(request.getColor()));
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        return get(categoryRepository.save(category).getId());
    }

    @Override
    @Transactional
    public CategoryResponse setEnabled(Long id, boolean enabled) {
        Category category = find(id);
        category.setEnabled(enabled);
        categoryRepository.save(category);
        return get(id);
    }

    @Override
    @Transactional
    public int delete(Long id) {
        Category category = find(id);
        int detached = videoRepository.detachCategory(id);
        categoryRepository.delete(category);
        return detached;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> resolveForVideo(Collection<Long> requested, Set<Long> current) {
        Set<Long> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
        ids.remove(null);
        if (ids.isEmpty()) {
            return ids;
        }
        Map<Long, Category> found = categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<Long> missing =
                ids.stream().filter(Predicate.not(found::containsKey)).toList();
        if (!missing.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Unknown category id(s): " + missing);
        }
        List<String> disabled = ids.stream()
                .filter(cid -> !current.contains(cid) && !found.get(cid).isEnabled())
                .map(cid -> found.get(cid).getName())
                .toList();
        if (!disabled.isEmpty()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "Disabled categories can't be added: " + String.join(", ", disabled));
        }
        return ids;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Category find(Long id) {
        return categoryRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Category not found with id: " + id));
    }

    // An explicit slug must be free; a generated one gets "-2", "-3"… until it is.
    private String chooseSlug(String requested, String name, Long selfId) {
        if (requested != null && !requested.isBlank()) {
            String slug = TranscriptServiceImpl.slug(requested);
            boolean taken = selfId == null
                    ? categoryRepository.existsBySlug(slug)
                    : categoryRepository.existsBySlugAndIdNot(slug, selfId);
            if (taken) {
                throw new AppException(
                        HttpStatus.CONFLICT, "The slug \"" + slug + "\" is already used by another category");
            }
            return slug;
        }
        String base = TranscriptServiceImpl.slug(name);
        String slug = base;
        for (int n = 2; categoryRepository.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private Map<Long, Long> countVideos(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return videoRepository.countLiveByCategoryIds(ids).stream()
                .collect(Collectors.toMap(
                        VideoRepository.CategoryUsage::getCategoryId, VideoRepository.CategoryUsage::getCount));
    }

    private static CategoryResponse toResponse(Category c, Long videoCount) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .slug(c.getSlug())
                .description(c.getDescription())
                .color(c.getColor())
                .sortOrder(c.getSortOrder())
                .enabled(c.isEnabled())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .videoCount(videoCount)
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
