package com.example.videolingo.pipeline;

import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoClip;
import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.VideoClipRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.service.VideoVersionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Trimming/cropping/scaling a range of a video, or splitting it into
// segments — both run as EDIT jobs, producing VideoClip rows an admin
// reviews before promoting (TRIM replaces the video's file, SPLIT segments
// become new videos) or discarding.
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoEditService {

    public record CropRect(int x, int y, int w, int h) {
    }

    public record ScaleSize(int w, int h) {
    }

    public record SegmentRange(long startMs, Long endMs) {
    }

    public record TrimRequest(long startMs, Long endMs, CropRect crop, ScaleSize scale) {
    }

    public record SplitRequest(List<SegmentRange> segments) {
    }

    public record ClipResponse(Long id, Long jobId, String operation, Integer segmentIndex, long startMs, Long endMs,
                               CropRect crop, ScaleSize scale, String url, long sizeBytes, Integer durationSeconds,
                               Integer width, Integer height, LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    public record EditOverview(List<ClipResponse> clips, List<ProcessingJobResponse> jobs) {
    }

    // kind: "REPLACED" (TRIM promoted onto the video) or "NEW_VIDEO" (a split segment became its own video).
    public record PromoteResult(String kind, Long newVideoId) {
    }

    private final VideoRepository videoRepository;
    private final VideoClipRepository clipRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobService jobService;
    private final PipelineSteps steps;
    private final VideoVersionService versionService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public EditOverview overview(Long videoId) {
        findVideo(videoId);
        List<ClipResponse> clips = clipRepository.findByVideoIdAndExpiresAtAfterOrderByIdDesc(videoId, LocalDateTime.now())
                .stream().map(VideoEditService::toResponse).toList();
        List<ProcessingJobResponse> jobs = jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.EDIT)
                .stream().map(j -> jobService.getJob(j.getId())).toList();
        return new EditOverview(clips, jobs);
    }

    @Transactional
    public ProcessingJobResponse startTrim(Long videoId, TrimRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        require(VideoEditRules.validateTrim(request.startMs(), request.endMs(), durationMs(video)));
        if (request.crop() != null) {
            require(VideoEditRules.validateCrop(request.crop().x(), request.crop().y(), request.crop().w(), request.crop().h(),
                    video.getWidth(), video.getHeight()));
        }
        if (request.scale() != null) {
            require(VideoEditRules.validateScale(request.scale().w(), request.scale().h()));
        }
        requireNoActiveJob(videoId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "TRIM");
        params.put("startMs", request.startMs());
        if (request.endMs() != null) {
            params.put("endMs", request.endMs());
        }
        if (request.crop() != null) {
            params.put("crop", Map.of("x", request.crop().x(), "y", request.crop().y(), "w", request.crop().w(), "h", request.crop().h()));
        }
        if (request.scale() != null) {
            params.put("scale", Map.of("w", request.scale().w(), "h", request.scale().h()));
        }
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params), "Trim/crop requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public ProcessingJobResponse startSplit(Long videoId, SplitRequest request, String username) {
        Video video = findVideo(videoId);
        requireEditable(video);
        List<SegmentRange> segments = request.segments() == null ? List.of() : request.segments();
        require(VideoEditRules.validateSegments(segments.stream().map(s -> new VideoEditRules.Segment(s.startMs(), s.endMs())).toList(),
                durationMs(video)));
        requireNoActiveJob(videoId);

        List<Map<String, Object>> segmentParams = segments.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", s.startMs());
            if (s.endMs() != null) {
                m.put("endMs", s.endMs());
            }
            return m;
        }).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operation", "SPLIT");
        params.put("segments", segmentParams);
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.EDIT, toJson(params),
                "Split into " + segments.size() + " segment(s) requested by " + username);
        return jobService.getJob(job.getId());
    }

    @Transactional
    public PromoteResult promote(Long videoId, Long clipId, String username) {
        VideoClip clip = findClip(videoId, clipId);
        if (clip.getOperation() == VideoClip.Operation.TRIM) {
            Video video = findVideo(videoId);
            // The superseded file becomes a version instead of being deleted.
            versionService.snapshot(video, "Replaced by a trim/crop edit", username);
            video.setStorageKey(clip.getStorageKey());
            video.setVideoUrl(clip.getUrl());
            video.setFileSize(clip.getSizeBytes());
            video.setMimeType("video/mp4");
            if (clip.getDurationSeconds() != null) {
                video.setDurationSeconds(clip.getDurationSeconds());
            }
            if (clip.getWidth() != null) {
                video.setWidth(clip.getWidth());
            }
            if (clip.getHeight() != null) {
                video.setHeight(clip.getHeight());
            }
            videoRepository.save(video);
            clipRepository.delete(clip);
            log.info("Video #{} replaced with an edited clip by {}", videoId, username);
            return new PromoteResult("REPLACED", null);
        }

        Video source = findVideo(videoId);
        String title = source.getTitle() + " — Part " + ((clip.getSegmentIndex() == null ? 0 : clip.getSegmentIndex()) + 1);
        Video created = videoRepository.save(Video.builder()
                .title(title)
                .ownerId(source.getOwnerId())
                .language(source.getLanguage())
                .videoUrl(clip.getUrl())
                .storageKey(clip.getStorageKey())
                .source(VideoSource.UPLOAD)
                .durationSeconds(clip.getDurationSeconds())
                .width(clip.getWidth())
                .height(clip.getHeight())
                .fileSize(clip.getSizeBytes())
                .mimeType("video/mp4")
                .enabled(false)
                .build());
        clipRepository.delete(clip);
        log.info("Video #{} created from a split segment of video #{} by {}", created.getId(), videoId, username);
        return new PromoteResult("NEW_VIDEO", created.getId());
    }

    @Transactional
    public void remove(Long videoId, Long clipId) {
        VideoClip clip = findClip(videoId, clipId);
        clipRepository.delete(clip);
        steps.deleteObject(clip.getStorageKey());
    }

    // Unpromoted clips are temporary: remove expired ones and their files.
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void deleteExpired() {
        List<VideoClip> expired = clipRepository.findByExpiresAtBefore(LocalDateTime.now());
        for (VideoClip c : expired) {
            steps.deleteObject(c.getStorageKey());
        }
        clipRepository.deleteAll(expired);
        if (!expired.isEmpty()) {
            log.info("Deleted {} expired video clip(s)", expired.size());
        }
    }

    private void requireEditable(Video video) {
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video first");
        }
        if (PipelineSteps.isLink(video.getSource())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Import this video into storage first — link videos can't be edited directly");
        }
    }

    private void requireNoActiveJob(Long videoId) {
        for (ProcessingJob j : jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.EDIT)) {
            if (j.getStatus().isActive()) {
                throw new AppException(HttpStatus.CONFLICT, "An edit is already " + j.getStatus().name().toLowerCase() + " (job #" + j.getId() + ")");
            }
        }
    }

    private static void require(String error) {
        if (error != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, error);
        }
    }

    private static Long durationMs(Video video) {
        return video.getDurationSeconds() == null ? null : video.getDurationSeconds() * 1000L;
    }

    private Video findVideo(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private VideoClip findClip(Long videoId, Long clipId) {
        return clipRepository.findById(clipId)
                .filter(c -> c.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Clip not found"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClipResponse toResponse(VideoClip c) {
        CropRect crop = c.hasCrop() ? new CropRect(c.getCropX(), c.getCropY(), c.getCropW(), c.getCropH()) : null;
        ScaleSize scale = c.getScaleW() != null && c.getScaleH() != null ? new ScaleSize(c.getScaleW(), c.getScaleH()) : null;
        return new ClipResponse(c.getId(), c.getJobId(), c.getOperation().name(), c.getSegmentIndex(), c.getStartMs(), c.getEndMs(),
                crop, scale, c.getUrl(), c.getSizeBytes(), c.getDurationSeconds(), c.getWidth(), c.getHeight(), c.getCreatedAt(), c.getExpiresAt());
    }
}
