package com.example.videolingo.pipeline;

import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.ReviewStatus;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoExport;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoExportRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.settings.SettingsService;
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

// Downloading a video as an MP4 (optionally with a voice-over as its sound)
// and importing a link video into our storage. Both run as DOWNLOAD jobs.
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoDownloadService {

    public enum Mode { FILE, IMPORT }

    // subtitleId: a track of this video to burn into the picture (FILE only).
    public record DownloadRequest(Mode mode, String audio, Boolean rightsConfirmed, Long subtitleId) {
    }

    public record ExportResponse(Long id, Long jobId, String audioLanguage, String subtitleLabel, String fileName, String url, long sizeBytes,
                                 LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    public record DownloadOverview(boolean isLink, boolean canImport, String importedFrom, List<ExportResponse> exports,
                                   List<ProcessingJobResponse> jobs) {
    }

    private final VideoRepository videoRepository;
    private final VideoExportRepository exportRepository;
    private final VideoDubRepository dubRepository;
    private final SubtitleRepository subtitleRepository;
    private final SettingsService settings;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobService jobService;
    private final PipelineSteps steps;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DownloadOverview overview(Long videoId) {
        Video video = findVideo(videoId);
        boolean link = PipelineSteps.isLink(video.getSource());
        List<ExportResponse> exports = exportRepository.findByVideoIdAndExpiresAtAfterOrderByIdDesc(videoId, LocalDateTime.now())
                .stream().map(VideoDownloadService::toResponse).toList();
        List<ProcessingJobResponse> jobs = jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DOWNLOAD)
                .stream().map(j -> jobService.getJob(j.getId())).toList();
        return new DownloadOverview(link, link && !video.isDeleted(), video.getImportedFrom(), exports, jobs);
    }

    @Transactional
    public ProcessingJobResponse start(Long videoId, DownloadRequest request, String username) {
        Video video = findVideo(videoId);
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video first");
        }
        Mode mode = request == null || request.mode() == null ? Mode.FILE : request.mode();
        String audio = request == null || request.audio() == null || request.audio().isBlank() ? null : request.audio().strip();
        boolean link = PipelineSteps.isLink(video.getSource());
        Long subtitleId = request == null ? null : request.subtitleId();
        Subtitle track = null;
        if (subtitleId != null) {
            if (mode == Mode.IMPORT) {
                throw new AppException(HttpStatus.BAD_REQUEST, "An import keeps the original picture; burn subtitles into a download instead");
            }
            track = subtitleRepository.findById(subtitleId)
                    .filter(s -> s.getVideoId().equals(videoId))
                    .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "That subtitle track isn't on this video"));
            if (track.getCueCount() == 0) {
                throw new AppException(HttpStatus.BAD_REQUEST, "The track “" + track.getLabel() + "” has no cues to burn in");
            }
            // Burning in publishes the text in a file of its own, so the publishing rule applies.
            if (settings.translation().requireApprovalToPublish() && track.reviewStatus() != ReviewStatus.APPROVED) {
                throw new AppException(HttpStatus.CONFLICT, "Get “" + track.getLabel() + "” approved before burning it in (Settings › Translation requires review first)");
            }
        }

        if (mode == Mode.IMPORT && !link) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Only YouTube, Vimeo and Facebook videos can be imported — this one is already a file");
        }
        if (mode == Mode.IMPORT && audio != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "An import keeps the original sound; voice-overs play alongside it");
        }
        if (mode == Mode.FILE && !link && audio == null && track == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "This video is already a file — download it directly");
        }
        if (audio != null && dubRepository.findByVideoIdAndLanguage(videoId, audio).isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "This video has no " + audio + " voice-over — create it first");
        }
        // Platforms' terms only allow downloading with the owner's permission.
        if (link && (request == null || !Boolean.TRUE.equals(request.rightsConfirmed()))) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Confirm you have the rights to download this video");
        }
        for (ProcessingJob j : jobRepository.findTop10ByVideoIdAndTypeOrderByIdDesc(videoId, ProcessingJobType.DOWNLOAD)) {
            if (j.getStatus().isActive()) {
                throw new AppException(HttpStatus.CONFLICT, "A download is already " + j.getStatus().name().toLowerCase() + " (job #" + j.getId() + ")");
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", mode.name());
        if (audio != null) {
            params.put("audio", audio);
        }
        if (track != null) {
            params.put("subtitleId", track.getId());
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String what = mode == Mode.IMPORT ? "Import into storage" : "Download" + (audio != null ? " with " + audio + " voice-over" : "")
                + (track != null ? (audio != null ? " and" : " with") + " “" + track.getLabel() + "” subtitles burned in" : "");
        ProcessingJob job = jobService.enqueue(videoId, ProcessingJobType.DOWNLOAD, json,
                what + " requested by " + username + (link ? " (confirmed they have the rights to this video)" : ""));
        return jobService.getJob(job.getId());
    }

    @Transactional
    public void deleteExport(Long videoId, Long exportId) {
        VideoExport export = exportRepository.findById(exportId)
                .filter(e -> e.getVideoId().equals(videoId))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Download not found"));
        exportRepository.delete(export);
        steps.deleteObject(export.getStorageKey());
    }

    // Prepared downloads are temporary: remove expired ones and their files.
    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void deleteExpired() {
        List<VideoExport> expired = exportRepository.findByExpiresAtBefore(LocalDateTime.now());
        for (VideoExport e : expired) {
            steps.deleteObject(e.getStorageKey());
        }
        exportRepository.deleteAll(expired);
        if (!expired.isEmpty()) {
            log.info("Deleted {} expired video download(s)", expired.size());
        }
    }

    private Video findVideo(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + videoId));
    }

    private static ExportResponse toResponse(VideoExport e) {
        return new ExportResponse(e.getId(), e.getJobId(), e.getAudioLanguage(), e.getSubtitleLabel(), e.getFileName(), e.getUrl(), e.getSizeBytes(),
                e.getCreatedAt(), e.getExpiresAt());
    }
}
