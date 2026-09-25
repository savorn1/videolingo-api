package com.example.videolingo.service.impl;

import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobFilterRequest;
import com.example.videolingo.dto.ProcessingJobLogResponse;
import com.example.videolingo.dto.ProcessingJobProgressResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobLog;
import com.example.videolingo.entity.ProcessingJobStatus;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobLogRepository;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.util.PageableUtils;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcessingJobServiceImpl implements ProcessingJobService {

    private static final Set<String> SORTABLE = Set.of(
            "id", "type", "status", "progress", "attempts", "createdAt", "startedAt", "finishedAt", "updatedAt");
    private static final int MAX_LOG_PAGE = 1000;

    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobLogRepository logRepository;
    private final VideoRepository videoRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProcessingJobResponse> listJobs(ProcessingJobFilterRequest filter) {
        List<Specification<ProcessingJob>> conditions = new ArrayList<>();

        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String term = filter.getSearch().trim();
            String pattern = "%" + term.toLowerCase() + "%";
            Long idMatch = parseId(term);
            conditions.add((root, query, cb) -> {
                Subquery<Long> videoIds = query.subquery(Long.class);
                var video = videoIds.from(Video.class);
                videoIds.select(video.get("id")).where(cb.like(cb.lower(video.get("title")), pattern));
                Predicate byVideo = root.get("videoId").in(videoIds);
                return idMatch == null ? byVideo : cb.or(byVideo, cb.equal(root.get("id"), idMatch));
            });
        }
        if (filter.getStatus() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("status"), filter.getStatus()));
        }
        if (filter.getType() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("type"), filter.getType()));
        }
        if (filter.getVideoId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("videoId"), filter.getVideoId()));
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
        Page<ProcessingJob> jobs = jobRepository.findAll(Specification.allOf(conditions), pageable);

        Map<Long, String> titles = videoRepository.findAllById(
                jobs.getContent().stream().map(ProcessingJob::getVideoId).filter(Objects::nonNull).distinct().toList()
        ).stream().collect(Collectors.toMap(Video::getId, Video::getTitle));

        // logCount is left at 0 in the list — it's only shown on the detail page.
        return PageResponse.of(jobs.map(j -> toResponse(j, titles.get(j.getVideoId()), 0)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ProcessingJobStatus status : ProcessingJobStatus.values()) {
            counts.put(status.name(), 0L);
        }
        jobRepository.countByStatus().forEach(c -> counts.put(c.getStatus().name(), c.getCount()));
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessingJobResponse getJob(Long id) {
        return toDetailResponse(findJob(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessingJobProgressResponse getProgress(Long id) {
        ProcessingJob job = findJob(id);
        return ProcessingJobProgressResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .progress(job.getProgress())
                .currentStep(job.getCurrentStep())
                .errorMessage(job.getErrorMessage())
                .durationSeconds(durationOf(job))
                .updatedAt(job.getUpdatedAt())
                .lastLogId(lastLogId(id))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessingJobLogResponse> getLogs(Long id, long afterId, int limit) {
        findJob(id);
        int pageSize = Math.max(1, Math.min(limit, MAX_LOG_PAGE));
        return logRepository.findAfter(id, Math.max(0, afterId), PageRequest.of(0, pageSize)).stream()
                .map(l -> ProcessingJobLogResponse.builder()
                        .id(l.getId())
                        .level(l.getLevel().name())
                        .message(l.getMessage())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public ProcessingJobResponse retry(Long id, String actingUsername) {
        ProcessingJob job = findJob(id);
        if (!canRetry(job.getStatus())) {
            throw new AppException(HttpStatus.CONFLICT, "Only failed or cancelled jobs can be retried (this one is " + job.getStatus() + ")");
        }
        job.setStatus(ProcessingJobStatus.QUEUED);
        job.setProgress(0);
        job.setCurrentStep(null);
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        // attempts is left alone — the worker increments it when it picks the
        // job up, so the count stays an honest record of real executions.
        saveGuarded(job);
        log(job.getId(), ProcessingJobLog.Level.INFO, "Re-queued by " + actingUsername);
        return toDetailResponse(job);
    }

    @Override
    @Transactional
    public ProcessingJobResponse cancel(Long id, String actingUsername) {
        ProcessingJob job = findJob(id);
        if (!job.getStatus().isActive()) {
            throw new AppException(HttpStatus.CONFLICT, "Only queued or running jobs can be cancelled (this one is " + job.getStatus() + ")");
        }
        boolean wasRunning = job.getStatus() == ProcessingJobStatus.RUNNING;
        job.setStatus(ProcessingJobStatus.CANCELLED);
        job.setFinishedAt(LocalDateTime.now());
        job.setCurrentStep(null);
        saveGuarded(job);
        log(job.getId(), ProcessingJobLog.Level.WARN, "Cancelled by " + actingUsername
                + (wasRunning ? " — the worker stops at its next checkpoint" : ""));
        return toDetailResponse(job);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ProcessingJob job = findJob(id);
        if (job.getStatus() == ProcessingJobStatus.RUNNING) {
            throw new AppException(HttpStatus.CONFLICT, "Cancel the job before deleting it — it's still running");
        }
        logRepository.deleteByJobId(id);
        jobRepository.delete(job);
    }

    @Override
    @Transactional
    public ProcessingJob enqueue(Long videoId, ProcessingJobType type, String parametersJson, String reason) {
        ProcessingJob job = jobRepository.save(ProcessingJob.builder()
                .videoId(videoId)
                .type(type)
                .status(ProcessingJobStatus.QUEUED)
                .parameters(parametersJson)
                .build());
        log(job.getId(), ProcessingJobLog.Level.INFO, reason);
        return job;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private ProcessingJob findJob(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Processing job not found with id: " + id));
    }

    // The worker may have moved the job on between our read and write — surface
    // that as a clean 409 instead of a 500, so the admin can refresh and retry.
    private void saveGuarded(ProcessingJob job) {
        try {
            jobRepository.saveAndFlush(job);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new AppException(HttpStatus.CONFLICT, "The job changed while you were looking at it — refresh and try again");
        }
    }

    private void log(Long jobId, ProcessingJobLog.Level level, String message) {
        logRepository.save(ProcessingJobLog.builder().jobId(jobId).level(level).message(message).build());
    }

    private Long lastLogId(Long jobId) {
        return logRepository.findFirstByJobIdOrderByIdDesc(jobId).map(ProcessingJobLog::getId).orElse(null);
    }

    private static boolean canRetry(ProcessingJobStatus status) {
        return status == ProcessingJobStatus.FAILED || status == ProcessingJobStatus.CANCELLED;
    }

    private static Long durationOf(ProcessingJob job) {
        if (job.getStartedAt() == null) {
            return null;
        }
        LocalDateTime end = job.getFinishedAt() != null ? job.getFinishedAt() : LocalDateTime.now();
        return Math.max(0, Duration.between(job.getStartedAt(), end).getSeconds());
    }

    private static Long parseId(String term) {
        String digits = term.startsWith("#") ? term.substring(1) : term;
        try {
            return digits.matches("\\d{1,18}") ? Long.valueOf(digits) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ProcessingJobResponse toDetailResponse(ProcessingJob job) {
        String title = videoRepository.findById(job.getVideoId()).map(Video::getTitle).orElse(null);
        return toResponse(job, title, logRepository.countByJobId(job.getId()));
    }

    private ProcessingJobResponse toResponse(ProcessingJob job, String videoTitle, long logCount) {
        ProcessingJobStatus status = job.getStatus();
        return ProcessingJobResponse.builder()
                .id(job.getId())
                .videoId(job.getVideoId())
                .videoTitle(videoTitle)
                .type(job.getType())
                .status(status)
                .progress(job.getProgress())
                .currentStep(job.getCurrentStep())
                .parameters(job.getParameters())
                .attempts(job.getAttempts())
                .maxAttempts(job.getMaxAttempts())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .updatedAt(job.getUpdatedAt())
                .durationSeconds(durationOf(job))
                .logCount(logCount)
                .canRetry(canRetry(status))
                .canCancel(status.isActive())
                .canDelete(status != ProcessingJobStatus.RUNNING)
                .build();
    }
}
