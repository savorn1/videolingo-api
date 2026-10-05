package com.example.videolingo.service.impl;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.UpdateVideoRequest;
import com.example.videolingo.dto.VideoCategoryDto;
import com.example.videolingo.dto.VideoFilterRequest;
import com.example.videolingo.dto.VideoResponse;
import com.example.videolingo.dto.VideoStatisticsResponse;
import com.example.videolingo.dto.VideoStatisticsResponse.DailyViewCount;
import com.example.videolingo.dto.VideoTagDto;
import com.example.videolingo.entity.Category;
import com.example.videolingo.entity.Tag;
import com.example.videolingo.entity.User;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.ingest.VideoLinks;
import com.example.videolingo.pipeline.DubService;
import com.example.videolingo.repository.CategoryRepository;
import com.example.videolingo.repository.TagRepository;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.repository.VideoViewRepository;
import com.example.videolingo.service.CategoryService;
import com.example.videolingo.service.FileStorageService;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.VideoMarkerService;
import com.example.videolingo.service.VideoService;
import com.example.videolingo.service.VideoVersionService;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.storage.MediaUrls;
import com.example.videolingo.util.PageableUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    // Whitelisted so a crafted sortBy can't reach arbitrary entity paths.
    private static final Set<String> SORTABLE = Set.of(
            "id", "title", "language", "durationSeconds", "fileSize", "enabled", "createdAt", "updatedAt", "deletedAt");
    private static final int MAX_STAT_DAYS = 365;

    private final VideoRepository videoRepository;
    private final MediaUrls mediaUrls;
    private final VideoViewRepository videoViewRepository;
    private final UserRepository userRepository;
    private final LanguageService languageService;
    private final CategoryService categoryService;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final SettingsService settings;
    private final VideoVersionService videoVersionService;
    private final VideoMarkerService videoMarkerService;
    private final DubService dubService;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VideoResponse> listVideos(VideoFilterRequest filter) {
        List<Specification<Video>> conditions = new ArrayList<>();

        conditions.add(
                filter.isDeleted()
                        ? (root, query, cb) -> cb.isNotNull(root.get("deletedAt"))
                        : (root, query, cb) -> cb.isNull(root.get("deletedAt")));
        // Archived is its own axis, only meaningful outside the trash (Videos / Archived / Trash tabs).
        if (!filter.isDeleted()) {
            conditions.add(
                    filter.isArchived()
                            ? (root, query, cb) -> cb.isNotNull(root.get("archivedAt"))
                            : (root, query, cb) -> cb.isNull(root.get("archivedAt")));
        }
        if (filter.getVisibility() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("visibility"), filter.getVisibility()));
        }
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + filter.getSearch().trim().toLowerCase() + "%";
            conditions.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)));
        }
        if (filter.getOwnerId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("ownerId"), filter.getOwnerId()));
        }
        if (filter.getLanguage() != null && !filter.getLanguage().isBlank()) {
            conditions.add((root, query, cb) -> cb.equal(root.get("language"), filter.getLanguage()));
        }
        if (filter.getEnabled() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("enabled"), filter.getEnabled()));
        }
        if (filter.getTagId() != null) {
            conditions.add((root, query, cb) -> cb.isMember(filter.getTagId(), root.<Collection<Long>>get("tagIds")));
        }
        if (filter.getCategoryId() != null) {
            conditions.add((root, query, cb) ->
                    cb.isMember(filter.getCategoryId(), root.<Collection<Long>>get("categoryIds")));
        }
        if (filter.getCreatedFrom() != null) {
            LocalDateTime from = filter.getCreatedFrom().atStartOfDay();
            conditions.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (filter.getCreatedTo() != null) {
            LocalDateTime toExclusive = filter.getCreatedTo().plusDays(1).atStartOfDay();
            conditions.add((root, query, cb) -> cb.lessThan(root.get("createdAt"), toExclusive));
        }

        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "id";
        Pageable pageable = PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder());
        Page<Video> videos = videoRepository.findAll(Specification.allOf(conditions), pageable);

        // Owner names and view counts are batch-resolved for the page, not per row.
        List<Video> content = videos.getContent();
        Map<Long, String> ownerNames = userRepository
                .findAllById(content.stream()
                        .map(Video::getOwnerId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        Map<Long, Long> viewCounts = content.isEmpty()
                ? Map.of()
                : videoViewRepository
                        .countByVideoIds(content.stream().map(Video::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                VideoViewRepository.VideoViewCount::getVideoId,
                                VideoViewRepository.VideoViewCount::getViews));

        return PageResponse.of(videos.map(v -> toResponse(
                v,
                v.getOwnerId() == null ? null : ownerNames.get(v.getOwnerId()),
                viewCounts.getOrDefault(v.getId(), 0L))));
    }

    @Override
    @Transactional(readOnly = true)
    public VideoResponse getVideo(Long id) {
        return toResponse(findVideo(id));
    }

    @Override
    @Transactional
    public VideoResponse updateVideo(Long id, UpdateVideoRequest request) {
        Video video = requireLive(findVideo(id), "edit");
        video.setTitle(request.getTitle().trim());
        video.setDescription(blankToNull(request.getDescription()));
        String language = blankToNull(request.getLanguage());
        if (language != null) {
            boolean unchanged = language.equalsIgnoreCase(video.getLanguage());
            language = languageService.resolve(language, !unchanged);
        }
        video.setLanguage(language);
        // A thumbnail shown in the form comes back signed; keep the plain address.
        video.setThumbnailUrl(mediaUrls.toStored(blankToNull(request.getThumbnailUrl())));
        if (request.getVisibility() != null) {
            video.setVisibility(request.getVisibility());
        }
        if (request.getCategoryIds() != null) {
            Set<Long> resolved = categoryService.resolveForVideo(request.getCategoryIds(), video.getCategoryIds());
            int maxCategories = settings.video().maxCategoriesPerVideo();
            if (resolved.size() > maxCategories) {
                throw new AppException(
                        HttpStatus.BAD_REQUEST,
                        "A video can be in at most " + maxCategories + " categor" + (maxCategories == 1 ? "y" : "ies"));
            }
            video.getCategoryIds().clear();
            video.getCategoryIds().addAll(resolved);
        }
        if (video.getCategoryIds().isEmpty() && settings.video().requireCategory()) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "Choose at least one category — Settings › Video requires one");
        }
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public VideoResponse updateStatus(Long id, boolean enabled) {
        Video video = requireLive(findVideo(id), enabled ? "enable" : "disable");
        video.setEnabled(enabled);
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public void deleteVideo(Long id) {
        Video video = findVideo(id);
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Video is already in the trash");
        }
        video.setDeletedAt(LocalDateTime.now());
        videoRepository.save(video);
    }

    @Override
    @Transactional
    public VideoResponse restoreVideo(Long id) {
        Video video = findVideo(id);
        if (!video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Video is not in the trash");
        }
        video.setDeletedAt(null);
        return toResponse(videoRepository.save(video));
    }

    // Permanently deletes one trashed video: its file, version history and
    // dub tracks. See purgeTrash for what's deliberately left behind.
    @Override
    @Transactional
    public void purgeVideo(Long id) {
        Video video = findVideo(id);
        if (!video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Move the video to the trash before deleting it permanently");
        }
        purgeFiles(video);
        videoRepository.delete(video);
    }

    // Permanently deletes every trashed video: its file, version history and
    // dub tracks. Rows in unrelated tables (AI usage, watch progress, old
    // processing jobs…) that reference the video id are left as harmless
    // orphaned history — cleaning those up is a separate, larger undertaking.
    // A failure partway through (e.g. a bad S3 key) doesn't abort the rest;
    // S3 deletes are already best-effort in the services below.
    @Override
    @Transactional
    public int purgeTrash() {
        List<Video> trashed = videoRepository.findByDeletedAtIsNotNull();
        trashed.forEach(this::purgeFiles);
        videoRepository.deleteAll(trashed);
        return trashed.size();
    }

    private void purgeFiles(Video video) {
        dubService.purgeAllForVideo(video.getId());
        videoMarkerService.purgeAllForVideo(video.getId());
        videoVersionService.purgeAll(video.getId());
        if (video.getStorageKey() != null) {
            try {
                fileStorageService.delete(video.getStorageKey());
            } catch (RuntimeException ignored) {
                // An orphaned file costs a little storage; not worth failing the purge.
            }
        }
    }

    @Override
    @Transactional
    public VideoResponse archiveVideo(Long id) {
        Video video = requireLive(findVideo(id), "archive");
        if (video.isArchived()) {
            throw new AppException(HttpStatus.CONFLICT, "Video is already archived");
        }
        video.setArchivedAt(LocalDateTime.now());
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public VideoResponse unarchiveVideo(Long id) {
        Video video = findVideo(id);
        if (!video.isArchived()) {
            throw new AppException(HttpStatus.CONFLICT, "Video is not archived");
        }
        video.setArchivedAt(null);
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public VideoResponse moveOwner(Long id, Long newOwnerId) {
        Video video = findVideo(id);
        if (newOwnerId != null && !userRepository.existsById(newOwnerId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "User not found with id: " + newOwnerId);
        }
        video.setOwnerId(newOwnerId);
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public VideoResponse duplicate(Long id, String actingUsername) {
        Video source = findVideo(id);
        Long ownerId =
                userRepository.findByUsername(actingUsername).map(User::getId).orElse(source.getOwnerId());
        Video copy = Video.builder()
                .title(source.getTitle() + " (copy)")
                .description(source.getDescription())
                .ownerId(ownerId)
                .language(source.getLanguage())
                .videoUrl(source.getVideoUrl())
                .storageKey(source.getStorageKey())
                .source(source.getSource())
                .externalId(null)
                .sourceAuthor(source.getSourceAuthor())
                .thumbnailUrl(source.getThumbnailUrl())
                .durationSeconds(source.getDurationSeconds())
                .durationMs(source.getDurationMs())
                .width(source.getWidth())
                .height(source.getHeight())
                .fileSize(source.getFileSize())
                .mimeType(source.getMimeType())
                .visibility(source.getVisibility())
                .enabled(false)
                .build();
        copy.getCategoryIds().addAll(source.getCategoryIds());
        copy.getTagIds().addAll(source.getTagIds());
        return toResponse(videoRepository.save(copy));
    }

    @Override
    @Transactional(readOnly = true)
    public VideoStatisticsResponse getStatistics(Long id, int days) {
        Video video = findVideo(id);
        int window = Math.max(1, Math.min(days, MAX_STAT_DAYS));

        VideoViewRepository.ViewTotals totals = videoViewRepository.totalsFor(video.getId());
        long totalViews = totals.getTotalViews();
        double averageWatch = totalViews == 0 ? 0 : (double) totals.getTotalWatchSeconds() / totalViews;
        Double averagePercent = video.getDurationSeconds() == null || video.getDurationSeconds() <= 0 || totalViews == 0
                ? null
                : round1(Math.min(100, averageWatch / video.getDurationSeconds() * 100));

        // Zero-fill so the chart has one bar per day, including quiet days.
        LocalDate firstDay = LocalDate.now().minusDays(window - 1L);
        Map<LocalDate, Long> counts =
                videoViewRepository.dailyViewsSince(video.getId(), firstDay.atStartOfDay()).stream()
                        .collect(Collectors.toMap(
                                VideoViewRepository.DailyViews::getDay, VideoViewRepository.DailyViews::getViews));
        List<DailyViewCount> daily = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate day = firstDay.plusDays(i);
            daily.add(new DailyViewCount(day, counts.getOrDefault(day, 0L)));
        }

        return VideoStatisticsResponse.builder()
                .totalViews(totalViews)
                .uniqueViewers(totals.getUniqueViewers())
                .totalWatchSeconds(totals.getTotalWatchSeconds())
                .averageWatchSeconds(round1(averageWatch))
                .completionRate(totalViews == 0 ? 0 : round1((double) totals.getCompletedViews() / totalViews * 100))
                .averagePercentWatched(averagePercent)
                .lastViewedAt(totals.getLastViewedAt())
                .days(window)
                .dailyViews(daily)
                .build();
    }

    private Video findVideo(Long id) {
        return videoRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + id));
    }

    private Video requireLive(Video video, String action) {
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before you " + action + " it");
        }
        return video;
    }

    private VideoResponse toResponse(Video video) {
        String ownerUsername = video.getOwnerId() == null
                ? null
                : userRepository
                        .findById(video.getOwnerId())
                        .map(User::getUsername)
                        .orElse(null);
        long views = videoViewRepository.countByVideoIds(List.of(video.getId())).stream()
                .mapToLong(VideoViewRepository.VideoViewCount::getViews)
                .sum();
        return toResponse(video, ownerUsername, views);
    }

    static VideoSource sourceOf(Video video) {
        if (video.getSource() != null) {
            return video.getSource();
        }
        return video.getStorageKey() != null ? VideoSource.UPLOAD : VideoSource.URL;
    }

    private VideoResponse toResponse(Video video, String ownerUsername, long viewCount) {
        return VideoResponse.builder()
                .id(video.getId())
                .title(video.getTitle())
                .description(video.getDescription())
                .ownerId(video.getOwnerId())
                .ownerUsername(ownerUsername)
                .language(video.getLanguage())
                .videoUrl(mediaUrls.forBrowser(video.getVideoUrl()))
                .storageKey(video.getStorageKey())
                .importedFrom(video.getImportedFrom())
                .source(sourceOf(video))
                .externalId(video.getExternalId())
                .sourceAuthor(video.getSourceAuthor())
                .embedUrl(VideoLinks.embedUrl(sourceOf(video), video.getExternalId(), video.getVideoUrl()))
                .thumbnailUrl(mediaUrls.forBrowser(video.getThumbnailUrl()))
                .durationSeconds(video.getDurationSeconds())
                .durationMs(video.getDurationMs())
                .width(video.getWidth())
                .height(video.getHeight())
                .fileSize(video.getFileSize())
                .mimeType(video.getMimeType())
                .enabled(video.isEnabled())
                .archived(video.isArchived())
                .archivedAt(video.getArchivedAt())
                .visibility(video.getVisibility())
                .deleted(video.isDeleted())
                .deletedAt(video.getDeletedAt())
                .createdAt(video.getCreatedAt())
                .updatedAt(video.getUpdatedAt())
                .viewCount(viewCount)
                .categories(categoriesOf(video))
                .tags(tagsOf(video))
                .build();
    }

    @Override
    @Transactional
    public VideoResponse assignTags(Long id, List<Long> tagIds) {
        Video video = requireLive(findVideo(id), "tag");
        Set<Long> requested = new LinkedHashSet<>(tagIds);
        requested.remove(null);
        List<Long> found =
                tagRepository.findAllById(requested).stream().map(Tag::getId).toList();
        List<Long> missing = requested.stream().filter(t -> !found.contains(t)).toList();
        if (!missing.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Unknown tag id(s): " + missing);
        }
        Set<Long> merged = new LinkedHashSet<>(video.getTagIds());
        merged.addAll(requested);
        int maxTags = settings.video().maxTagsPerVideo();
        if (merged.size() > maxTags) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST,
                    "A video can have at most " + maxTags + " tags (this would make " + merged.size() + ")");
        }
        video.getTagIds().addAll(requested);
        return toResponse(videoRepository.save(video));
    }

    @Override
    @Transactional
    public VideoResponse removeTag(Long id, Long tagId) {
        Video video = requireLive(findVideo(id), "untag");
        // Idempotent: removing a tag the video doesn't have is a no-op, not an
        // error — a double click or a stale page shouldn't show a failure.
        video.getTagIds().remove(tagId);
        return toResponse(videoRepository.save(video));
    }

    private List<VideoTagDto> tagsOf(Video video) {
        if (video.getTagIds().isEmpty()) {
            return List.of();
        }
        return tagRepository.findAllById(video.getTagIds()).stream()
                .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new VideoTagDto(t.getId(), t.getName(), t.getSlug()))
                .toList();
    }

    // Ordered for display (sortOrder, then name), not in assignment order.
    private List<VideoCategoryDto> categoriesOf(Video video) {
        if (video.getCategoryIds().isEmpty()) {
            return List.of();
        }
        return categoryRepository.findAllById(video.getCategoryIds()).stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder)
                        .thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new VideoCategoryDto(c.getId(), c.getName(), c.getColor(), c.isEnabled()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
