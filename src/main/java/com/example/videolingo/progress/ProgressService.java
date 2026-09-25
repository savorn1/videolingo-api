package com.example.videolingo.progress;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoView;
import com.example.videolingo.entity.WatchProgress;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.repository.VideoViewRepository;
import com.example.videolingo.repository.WatchProgressRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Per-user watch progress, fed by the player's heartbeats. Each heartbeat
// also keeps that sitting's VideoView row up to date (one row per player
// session), which is what video statistics and analytics count.
@Service
@RequiredArgsConstructor
public class ProgressService {

    public record Heartbeat(
            // A random id per player session (a page showing a video).
            @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{8,40}$") String sessionId,
            @NotNull @Min(0) @Max(86_400) Double positionSeconds,
            @Min(0) @Max(86_400) Double durationSeconds,
            // Seconds actually spent playing since the previous heartbeat.
            @Min(0) @Max(86_400) Double watchedSeconds,
            boolean ended) {
    }

    public record ProgressDto(Long videoId, int positionSeconds, Integer durationSeconds, long watchedSeconds, boolean completed,
                              LocalDateTime completedAt, LocalDateTime lastWatchedAt, Integer percent) {
    }

    public record ContinueItem(ProgressDto progress, String title, String thumbnailUrl, String language, Integer videoDurationSeconds) {
    }

    private final WatchProgressRepository progressRepository;
    private final VideoViewRepository viewRepository;
    private final VideoRepository videoRepository;

    @Transactional
    public ProgressDto record(Long userId, Long videoId, Heartbeat beat) {
        Video video = videoRepository.findById(videoId)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
        LocalDateTime now = LocalDateTime.now();

        WatchProgress p = progressRepository.findByUserIdAndVideoId(userId, videoId)
                .orElseGet(() -> WatchProgress.builder().userId(userId).videoId(videoId).lastWatchedAt(now).build());
        Long sinceLast = p.getId() == null ? null : Duration.between(p.getLastWatchedAt(), now).toSeconds();
        long delta = ProgressRules.acceptedDelta(beat.watchedSeconds() == null ? 0 : beat.watchedSeconds(), sinceLast);
        Double duration = beat.durationSeconds() != null && beat.durationSeconds() > 0 ? beat.durationSeconds()
                : video.getDurationSeconds() != null ? video.getDurationSeconds().doubleValue() : null;
        boolean completesNow = ProgressRules.completes(beat.ended(), beat.positionSeconds(), duration);

        // Finishing puts the resume point back at the start.
        p.setPositionSeconds(completesNow ? 0 : (int) Math.floor(beat.positionSeconds()));
        if (duration != null) {
            p.setDurationSeconds((int) Math.round(duration));
        }
        p.setWatchedSeconds(p.getWatchedSeconds() + delta);
        if (completesNow && !p.isCompleted()) {
            p.setCompleted(true);
            p.setCompletedAt(now);
        }
        p.setLastWatchedAt(now);
        p = progressRepository.save(p);

        // The sitting's view row: created by its first heartbeat, then kept current.
        VideoView view = viewRepository.findBySessionIdAndVideoId(beat.sessionId(), videoId)
                .filter(v -> userId.equals(v.getUserId()))
                .orElseGet(() -> VideoView.builder().videoId(videoId).userId(userId).sessionId(beat.sessionId()).viewedAt(now).build());
        view.setWatchedSeconds((int) Math.min(Integer.MAX_VALUE, view.getWatchedSeconds() + delta));
        view.setCompleted(view.isCompleted() || completesNow);
        viewRepository.save(view);

        return toDto(p);
    }

    @Transactional(readOnly = true)
    public List<ProgressDto> forVideos(Long userId, Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return List.of();
        }
        return progressRepository.findByUserIdAndVideoIdIn(userId, videoIds).stream().map(ProgressService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ContinueItem> continueWatching(Long userId, int limit) {
        List<WatchProgress> rows = progressRepository.inProgress(userId, ProgressRules.MIN_RESUME_SECONDS,
                PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
        Map<Long, Video> videos = videoRepository.findAllById(rows.stream().map(WatchProgress::getVideoId).toList()).stream()
                .collect(Collectors.toMap(Video::getId, Function.identity()));
        return rows.stream().filter(r -> videos.containsKey(r.getVideoId())).map(r -> {
            Video v = videos.get(r.getVideoId());
            return new ContinueItem(toDto(r), v.getTitle(), v.getThumbnailUrl(), v.getLanguage(), v.getDurationSeconds());
        }).toList();
    }

    /** Mark watched / unwatched by hand. Unwatched forgets the video's progress entirely. */
    @Transactional
    public ProgressDto setCompleted(Long userId, Long videoId, boolean completed) {
        if (!completed) {
            progressRepository.deleteByUserIdAndVideoId(userId, videoId);
            return new ProgressDto(videoId, 0, null, 0, false, null, null, 0);
        }
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
        LocalDateTime now = LocalDateTime.now();
        WatchProgress p = progressRepository.findByUserIdAndVideoId(userId, videoId)
                .orElseGet(() -> WatchProgress.builder().userId(userId).videoId(videoId).durationSeconds(video.getDurationSeconds()).build());
        p.setCompleted(true);
        p.setPositionSeconds(0);
        if (p.getCompletedAt() == null) {
            p.setCompletedAt(now);
        }
        p.setLastWatchedAt(now);
        return toDto(progressRepository.save(p));
    }

    private static ProgressDto toDto(WatchProgress p) {
        return new ProgressDto(p.getVideoId(), p.getPositionSeconds(), p.getDurationSeconds(), p.getWatchedSeconds(), p.isCompleted(),
                p.getCompletedAt(), p.getLastWatchedAt(), ProgressRules.percent(p.getPositionSeconds(), p.getDurationSeconds(), p.isCompleted()));
    }
}
