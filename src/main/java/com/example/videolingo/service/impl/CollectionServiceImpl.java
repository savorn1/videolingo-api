package com.example.videolingo.service.impl;

import com.example.videolingo.dto.CollectionFilterRequest;
import com.example.videolingo.dto.CollectionRequest;
import com.example.videolingo.dto.CollectionResponse;
import com.example.videolingo.dto.CollectionVideoResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.CollectionItem;
import com.example.videolingo.entity.User;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoCollection;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.CollectionItemRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.repository.VideoCollectionRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.CollectionService;
import com.example.videolingo.util.PageableUtils;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

    private static final Set<String> SORTABLE = Set.of("id", "title", "visibility", "videoCount", "createdAt", "updatedAt");
    static final int MAX_VIDEOS = 500;

    private final VideoCollectionRepository collectionRepository;
    private final CollectionItemRepository itemRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;

    // ── Read ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionResponse> list(CollectionFilterRequest filter) {
        List<Specification<VideoCollection>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + TranscriptServiceImpl.escapeLike(filter.getSearch().trim().toLowerCase()) + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (filter.getVisibility() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("visibility"), filter.getVisibility()));
        }
        if (filter.getOwnerId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("ownerId"), filter.getOwnerId()));
        }
        if (filter.getVideoId() != null) {
            conditions.add((root, query, cb) -> {
                Subquery<Long> containing = query.subquery(Long.class);
                var item = containing.from(CollectionItem.class);
                containing.select(item.get("collectionId")).where(cb.equal(item.get("videoId"), filter.getVideoId()));
                return root.get("id").in(containing);
            });
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "updatedAt";
        Page<VideoCollection> page = collectionRepository.findAll(Specification.allOf(conditions),
                PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder()));
        Map<Long, String> owners = userRepository.findAllById(
                page.getContent().stream().map(VideoCollection::getOwnerId).filter(Objects::nonNull).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return PageResponse.of(page.map(c -> toResponse(c, owners.get(c.getOwnerId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionResponse get(Long id) {
        VideoCollection c = find(id);
        CollectionResponse r = toResponse(c, ownerName(c.getOwnerId()));
        CollectionItemRepository.DurationSum totals = itemRepository.totals(id);
        r.setTotalDurationSeconds(totals.getTotalSeconds());
        r.setTrashedVideoCount(totals.getTrashed());
        r.setDisabledVideoCount(totals.getDisabled());
        return r;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionVideoResponse> videos(Long id, int page, int size) {
        find(id);
        Page<CollectionItem> items = itemRepository.findByCollectionIdOrderByPositionAsc(id,
                PageRequest.of(Math.max(page - 1, 0), Math.max(1, Math.min(size, 100))));
        Map<Long, Video> videos = videoRepository.findAllById(items.getContent().stream().map(CollectionItem::getVideoId).toList())
                .stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        return PageResponse.of(items.map(i -> {
            Video v = videos.get(i.getVideoId());
            return CollectionVideoResponse.builder()
                    .position(i.getPosition())
                    .videoId(i.getVideoId())
                    .title(v != null ? v.getTitle() : null)
                    .thumbnailUrl(v != null ? v.getThumbnailUrl() : null)
                    .durationSeconds(v != null ? v.getDurationSeconds() : null)
                    .language(v != null ? v.getLanguage() : null)
                    .enabled(v != null && v.isEnabled())
                    .deleted(v == null || v.isDeleted())
                    .addedBy(i.getAddedBy())
                    .addedAt(i.getAddedAt())
                    .build();
        }));
    }

    // ── Write ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CollectionResponse create(CollectionRequest request, String actingUsername) {
        Long ownerId = request.getOwnerId() != null ? requireUser(request.getOwnerId())
                : userRepository.findByUsername(actingUsername).map(User::getId).orElse(null);
        String title = request.getTitle().strip();
        VideoCollection collection = collectionRepository.save(VideoCollection.builder()
                .title(title)
                .slug(chooseSlug(request.getSlug(), title, null))
                .description(blankToNull(request.getDescription()))
                .coverUrl(blankToNull(request.getCoverUrl()))
                .visibility(request.getVisibility())
                .ownerId(ownerId)
                .build());
        if (request.getVideoIds() != null && !request.getVideoIds().isEmpty()) {
            append(collection, request.getVideoIds(), actingUsername);
        }
        return get(collection.getId());
    }

    @Override
    @Transactional
    public CollectionResponse update(Long id, CollectionRequest request) {
        VideoCollection collection = find(id);
        String title = request.getTitle().strip();
        collection.setTitle(title);
        if (request.getSlug() != null && !request.getSlug().isBlank()) {
            collection.setSlug(chooseSlug(request.getSlug(), title, id));
        }
        collection.setDescription(blankToNull(request.getDescription()));
        collection.setCoverUrl(blankToNull(request.getCoverUrl()));
        collection.setVisibility(request.getVisibility());
        if (request.getOwnerId() != null) {
            collection.setOwnerId(requireUser(request.getOwnerId()));
        }
        collectionRepository.save(collection);
        return get(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        VideoCollection collection = find(id);
        itemRepository.deleteByCollectionId(id);
        collectionRepository.delete(collection);
    }

    @Override
    @Transactional
    public int addVideos(Long id, List<Long> videoIds, String actingUsername) {
        return append(find(id), videoIds, actingUsername);
    }

    @Override
    @Transactional
    public CollectionResponse removeVideo(Long id, Long videoId) {
        VideoCollection collection = find(id);
        CollectionItem item = itemRepository.findByCollectionIdAndVideoId(id, videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video #" + videoId + " isn't in this collection"));
        itemRepository.delete(item);
        itemRepository.flush();
        itemRepository.shiftDownAfter(id, item.getPosition());
        // shiftDownAfter cleared the persistence context — reload before updating.
        collection = find(id);
        collection.setVideoCount(Math.max(0, collection.getVideoCount() - 1));
        collectionRepository.save(collection);
        return get(id);
    }

    @Override
    @Transactional
    public void reorder(Long id, List<Long> videoIds) {
        find(id);
        List<CollectionItem> items = itemRepository.findByCollectionIdOrderByPositionAsc(id);
        Map<Long, CollectionItem> byVideo = items.stream().collect(Collectors.toMap(CollectionItem::getVideoId, Function.identity()));
        if (videoIds == null || videoIds.size() != items.size() || !byVideo.keySet().equals(new HashSet<>(videoIds))) {
            // Someone added or removed a video since the list was loaded.
            throw new AppException(HttpStatus.CONFLICT, "This collection changed since you opened it — reload it and try again");
        }
        for (int i = 0; i < videoIds.size(); i++) {
            byVideo.get(videoIds.get(i)).setPosition(i);
        }
        itemRepository.saveAll(items);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    // Appends in request order, skipping duplicates and videos already present.
    // Trashed videos can't be added (they can stay if trashed after being added).
    private int append(VideoCollection collection, List<Long> requested, String actingUsername) {
        Set<Long> ids = new LinkedHashSet<>(requested);
        ids.remove(null);
        Map<Long, Video> found = videoRepository.findAllById(ids).stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        List<Long> missing = ids.stream().filter(v -> !found.containsKey(v)).toList();
        if (!missing.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Unknown video id(s): " + missing);
        }
        List<String> trashed = ids.stream().filter(v -> found.get(v).isDeleted()).map(v -> "#" + v).toList();
        if (!trashed.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Trashed videos can't be added: " + String.join(", ", trashed));
        }
        Set<Long> present = new HashSet<>(itemRepository.findVideoIds(collection.getId()));
        List<Long> toAdd = ids.stream().filter(v -> !present.contains(v)).toList();
        if (present.size() + toAdd.size() > MAX_VIDEOS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A collection can hold at most " + MAX_VIDEOS + " videos");
        }
        int next = itemRepository.maxPosition(collection.getId()) + 1;
        List<CollectionItem> items = new ArrayList<>();
        for (Long videoId : toAdd) {
            items.add(CollectionItem.builder().collectionId(collection.getId()).videoId(videoId).position(next++).addedBy(actingUsername).build());
        }
        itemRepository.saveAll(items);
        collection.setVideoCount(present.size() + toAdd.size());
        collectionRepository.save(collection);
        return toAdd.size();
    }

    private VideoCollection find(Long id) {
        return collectionRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Collection not found with id: " + id));
    }

    private Long requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "User not found with id: " + userId);
        }
        return userId;
    }

    private String ownerName(Long ownerId) {
        return ownerId == null ? null : userRepository.findById(ownerId).map(User::getUsername).orElse(null);
    }

    private String chooseSlug(String requested, String title, Long selfId) {
        if (requested != null && !requested.isBlank()) {
            String slug = TranscriptServiceImpl.slug(requested);
            boolean taken = selfId == null ? collectionRepository.existsBySlug(slug) : collectionRepository.existsBySlugAndIdNot(slug, selfId);
            if (taken) {
                throw new AppException(HttpStatus.CONFLICT, "The slug \"" + slug + "\" is already used by another collection");
            }
            return slug;
        }
        String base = TranscriptServiceImpl.slug(title);
        String slug = base;
        for (int n = 2; collectionRepository.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private static CollectionResponse toResponse(VideoCollection c, String ownerUsername) {
        return CollectionResponse.builder()
                .id(c.getId())
                .title(c.getTitle())
                .slug(c.getSlug())
                .description(c.getDescription())
                .coverUrl(c.getCoverUrl())
                .ownerId(c.getOwnerId())
                .ownerUsername(ownerUsername)
                .visibility(c.getVisibility())
                .videoCount(c.getVideoCount())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
