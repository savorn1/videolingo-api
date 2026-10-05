package com.example.videolingo.service;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoVersion;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.repository.VideoVersionRepository;
import com.example.videolingo.storage.MediaUrls;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

// A video's file, versioned: every time it's replaced (manual upload, or a
// promoted trim/crop edit) the previous file is kept here instead of being
// deleted, and can be restored later. Bounded to the last MAX_VERSIONS —
// older ones are pruned (row + S3 object) as new ones are added.
@Service
@RequiredArgsConstructor
public class VideoVersionService {

    private static final int MAX_VERSIONS = 20;

    public record VersionResponse(
            Long id,
            String url,
            Long fileSize,
            String mimeType,
            Integer durationSeconds,
            Integer width,
            Integer height,
            String note,
            String createdBy,
            LocalDateTime createdAt) {}

    private final VideoRepository videoRepository;
    private final MediaUrls mediaUrls;
    private final VideoVersionRepository versionRepository;
    private final S3Client s3;

    @Value("${s3.bucket:}")
    private String bucket;

    @Transactional(readOnly = true)
    public List<VersionResponse> list(Long videoId) {
        requireVideo(videoId);
        return versionRepository.findByVideoIdOrderByIdDesc(videoId).stream()
                .map(this::toResponse)
                .toList();
    }

    // No-op for a video with no file yet (a link video that's never been imported).
    @Transactional
    public void snapshot(Video video, String note, String actingUsername) {
        if (video.getStorageKey() == null) {
            return;
        }
        versionRepository.save(VideoVersion.builder()
                .videoId(video.getId())
                .storageKey(video.getStorageKey())
                .url(video.getVideoUrl())
                .fileSize(video.getFileSize())
                .mimeType(video.getMimeType())
                .durationSeconds(video.getDurationSeconds())
                .width(video.getWidth())
                .height(video.getHeight())
                .note(note)
                .createdBy(actingUsername)
                .build());
        prune(video.getId());
    }

    @Transactional
    public void restore(Long videoId, Long versionId, String actingUsername) {
        Video video = requireVideo(videoId);
        VideoVersion version = findVersion(videoId, versionId);
        // The current file becomes a version too, so restoring is never a one-way trip.
        snapshot(video, "Replaced by restoring an earlier version", actingUsername);
        video.setStorageKey(version.getStorageKey());
        // Versions keep only the rounded length; the exact one is re-read by the next edit.
        video.setDurationMs(null);
        video.setVideoUrl(version.getUrl());
        video.setFileSize(version.getFileSize());
        video.setMimeType(version.getMimeType());
        if (version.getDurationSeconds() != null) {
            video.setDurationSeconds(version.getDurationSeconds());
        }
        if (version.getWidth() != null) {
            video.setWidth(version.getWidth());
        }
        if (version.getHeight() != null) {
            video.setHeight(version.getHeight());
        }
        videoRepository.save(video);
        // Ownership of the file moved back onto the video row — no dual bookkeeping.
        versionRepository.delete(version);
    }

    @Transactional
    public void remove(Long videoId, Long versionId) {
        VideoVersion version = findVersion(videoId, versionId);
        versionRepository.delete(version);
        deleteObject(version.getStorageKey());
    }

    // Called when the video itself is being permanently purged — every kept
    // version's file goes with it, not just the pruned excess.
    @Transactional
    public void purgeAll(Long videoId) {
        List<VideoVersion> versions = versionRepository.findByVideoIdOrderByIdDesc(videoId);
        for (VideoVersion v : versions) {
            deleteObject(v.getStorageKey());
        }
        versionRepository.deleteAll(versions);
    }

    private void prune(Long videoId) {
        List<VideoVersion> versions = versionRepository.findByVideoIdOrderByIdDesc(videoId);
        if (versions.size() <= MAX_VERSIONS) {
            return;
        }
        List<VideoVersion> excess = versions.subList(MAX_VERSIONS, versions.size());
        for (VideoVersion v : excess) {
            deleteObject(v.getStorageKey());
        }
        versionRepository.deleteAll(excess);
    }

    private VideoVersion findVersion(Long videoId, Long versionId) {
        return versionRepository
                .findById(versionId)
                .filter(v -> v.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Version not found"));
    }

    private Video requireVideo(Long videoId) {
        return videoRepository
                .findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private void deleteObject(String key) {
        if (bucket == null || bucket.isBlank()) {
            return;
        }
        try {
            s3.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ignored) {
            // An orphaned file costs a little storage; not worth failing over.
        }
    }

    private VersionResponse toResponse(VideoVersion v) {
        return new VersionResponse(
                v.getId(),
                mediaUrls.forBrowser(v.getUrl()),
                v.getFileSize(),
                v.getMimeType(),
                v.getDurationSeconds(),
                v.getWidth(),
                v.getHeight(),
                v.getNote(),
                v.getCreatedBy(),
                v.getCreatedAt());
    }
}
