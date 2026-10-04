package com.example.videolingo.service.impl;

import com.example.videolingo.dto.CreateTranscriptRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.dto.TranscriptFilterRequest;
import com.example.videolingo.dto.TranscriptResponse;
import com.example.videolingo.dto.TranscriptSearchHit;
import com.example.videolingo.dto.TranscriptSearchRequest;
import com.example.videolingo.dto.TranscriptSegmentDto;
import com.example.videolingo.dto.UpdateTranscriptRequest;
import com.example.videolingo.entity.ContentRevision;
import com.example.videolingo.entity.ProcessingJob;
import com.example.videolingo.entity.ProcessingJobType;
import com.example.videolingo.entity.Transcript;
import com.example.videolingo.entity.TranscriptSegment;
import com.example.videolingo.entity.TranscriptSource;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ProcessingJobRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.TranscriptSegmentRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.revision.RevisionService;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.ProcessingJobService;
import com.example.videolingo.service.TranscriptService;
import com.example.videolingo.util.PageableUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Subquery;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TranscriptServiceImpl implements TranscriptService {

    private static final Set<String> SORTABLE =
            Set.of("id", "language", "source", "segmentCount", "wordCount", "durationMs", "createdAt", "updatedAt");
    // Scripts written without spaces between words count one "word" per
    // character; everything else counts runs of letters/digits.
    // ー (U+30FC, long-vowel mark) and 々 (U+3005, repetition mark) belong to
    // the Common script, not Katakana/Han, so they're listed explicitly —
    // otherwise they'd start a letter run that swallows the rest of the line.
    private static final String CJK = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u30FC\\u3005";
    private static final Pattern WORD = Pattern.compile("[" + CJK + "]|[[\\p{L}\\p{M}\\p{N}'’]&&[^" + CJK + "]]+");
    private static final int MIN_SEARCH_LENGTH = 2;

    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final VideoRepository videoRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingJobService processingJobService;
    private final LanguageService languageService;
    private final ObjectMapper objectMapper;
    private final RevisionService revisions;

    // ── List / get ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TranscriptResponse> listTranscripts(TranscriptFilterRequest filter) {
        List<Specification<Transcript>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + escapeLike(filter.getSearch().trim().toLowerCase()) + "%";
            conditions.add((root, query, cb) -> {
                Subquery<Long> videoIds = query.subquery(Long.class);
                var video = videoIds.from(Video.class);
                videoIds.select(video.get("id")).where(cb.like(cb.lower(video.get("title")), pattern, '\\'));
                return root.get("videoId").in(videoIds);
            });
        }
        if (filter.getVideoId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("videoId"), filter.getVideoId()));
        }
        if (filter.getLanguage() != null && !filter.getLanguage().isBlank()) {
            conditions.add((root, query, cb) -> cb.equal(root.get("language"), filter.getLanguage()));
        }
        if (filter.getSource() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("source"), filter.getSource()));
        }

        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "updatedAt";
        Pageable pageable = PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder());
        Page<Transcript> page = transcriptRepository.findAll(Specification.allOf(conditions), pageable);

        List<Transcript> content = page.getContent();
        Map<Long, Video> videos =
                videoRepository
                        .findAllById(content.stream()
                                .map(Transcript::getVideoId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Video::getId, Function.identity()));
        Map<Long, ProcessingJob> jobs = jobRepository
                .findAllById(content.stream()
                        .map(Transcript::getLastJobId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(ProcessingJob::getId, Function.identity()));

        return PageResponse.of(page.map(t -> toResponse(
                t, videos.get(t.getVideoId()), t.getLastJobId() == null ? null : jobs.get(t.getLastJobId()), null)));
    }

    @Override
    @Transactional(readOnly = true)
    public TranscriptResponse getTranscript(Long id) {
        return toDetailResponse(findTranscript(id));
    }

    // ── Create / update / delete ──────────────────────────────────────────

    @Override
    @Transactional
    public TranscriptResponse createTranscript(CreateTranscriptRequest request, String actingUsername) {
        Video video = videoRepository
                .findById(request.getVideoId())
                .orElseThrow(() ->
                        new AppException(HttpStatus.BAD_REQUEST, "Video not found with id: " + request.getVideoId()));
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before adding a transcript to it");
        }
        String language = languageService.resolve(request.getLanguage(), true);
        if (transcriptRepository.existsByVideoIdAndLanguage(video.getId(), language)) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "This video already has a " + language + " transcript — edit that one instead");
        }
        TranscriptSource source =
                request.getSource() == TranscriptSource.IMPORTED ? TranscriptSource.IMPORTED : TranscriptSource.MANUAL;
        List<TranscriptSegmentDto> segments = validateAndSort(request.getSegments());

        Transcript transcript = transcriptRepository.save(Transcript.builder()
                .videoId(video.getId())
                .language(language)
                .source(source)
                .createdBy(actingUsername)
                .updatedBy(actingUsername)
                .build());
        replaceSegments(transcript, segments);
        transcript = transcriptRepository.save(transcript);
        snapshot(transcript, source == TranscriptSource.IMPORTED ? "Imported" : "Created", actingUsername);
        return toDetailResponse(transcript);
    }

    @Override
    @Transactional
    public TranscriptResponse updateTranscript(Long id, UpdateTranscriptRequest request, String actingUsername) {
        Transcript transcript = findTranscript(id);
        if (!Objects.equals(transcript.getVersion(), request.getVersion())) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "This transcript changed since you opened it (maybe a regeneration finished). Reload it, then re-apply your edits.");
        }
        // Keeping the language it already has is allowed even if that language
        // was disabled since; switching to a different one requires it enabled.
        boolean unchanged = request.getLanguage() != null
                && request.getLanguage().strip().equalsIgnoreCase(transcript.getLanguage());
        String language = languageService.resolve(request.getLanguage(), !unchanged);
        if (transcriptRepository.existsByVideoIdAndLanguageAndIdNot(transcript.getVideoId(), language, id)) {
            throw new AppException(HttpStatus.CONFLICT, "This video already has a " + language + " transcript");
        }
        List<TranscriptSegmentDto> segments = validateAndSort(request.getSegments());

        transcript.setLanguage(language);
        // Any hand edit makes it a manual transcript, even if it started as AUTO.
        transcript.setSource(TranscriptSource.MANUAL);
        transcript.setUpdatedBy(actingUsername);
        replaceSegments(transcript, segments);
        transcript = transcriptRepository.saveAndFlush(transcript);
        snapshot(transcript, "Edited", actingUsername);
        return toDetailResponse(transcript);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionService.RevisionSummary> revisions(Long id) {
        findTranscript(id);
        return revisions.history(ContentRevision.EntityType.TRANSCRIPT, id);
    }

    @Override
    @Transactional(readOnly = true)
    public RevisionService.RevisionDetail<RevisionService.TranscriptSnapshot> revision(Long id, Long revisionId) {
        findTranscript(id);
        return revisions.get(
                ContentRevision.EntityType.TRANSCRIPT, id, revisionId, RevisionService.TranscriptSnapshot.class);
    }

    // Brings back that revision's segments as a new save; the language stays.
    @Override
    @Transactional
    public TranscriptResponse restoreRevision(Long id, Long revisionId, Long version, String actingUsername) {
        Transcript transcript = findTranscript(id);
        if (version != null && !Objects.equals(transcript.getVersion(), version)) {
            throw new AppException(
                    HttpStatus.CONFLICT, "This transcript changed since you opened it. Reload it, then try again.");
        }
        ProcessingJob job = activeJob(transcript);
        if (job != null) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "Regeneration job #" + job.getId() + " is still "
                            + job.getStatus().name().toLowerCase() + " — wait for it or cancel it first");
        }
        var revision = revisions.get(
                ContentRevision.EntityType.TRANSCRIPT, id, revisionId, RevisionService.TranscriptSnapshot.class);
        List<TranscriptSegmentDto> segments = revision.snapshot().segments() == null
                ? List.of()
                : revision.snapshot().segments();
        transcript.setSource(TranscriptSource.MANUAL);
        transcript.setUpdatedBy(actingUsername);
        replaceSegments(transcript, validateAndSort(segments));
        transcript = transcriptRepository.saveAndFlush(transcript);
        snapshot(transcript, "Restored revision " + revision.number(), actingUsername);
        return toDetailResponse(transcript);
    }

    @Override
    @Transactional
    public void deleteTranscript(Long id) {
        Transcript transcript = findTranscript(id);
        ProcessingJob job = activeJob(transcript);
        if (job != null) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "Regeneration job #" + job.getId() + " is still "
                            + job.getStatus().name().toLowerCase() + " — cancel it before deleting this transcript");
        }
        segmentRepository.deleteByTranscriptId(id);
        revisions.deleteAll(ContentRevision.EntityType.TRANSCRIPT, id);
        transcriptRepository.delete(transcript);
    }

    // ── Regenerate ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProcessingJobResponse regenerate(Long id, String actingUsername) {
        Transcript transcript = findTranscript(id);
        Video video = videoRepository
                .findById(transcript.getVideoId())
                .orElseThrow(
                        () -> new AppException(HttpStatus.CONFLICT, "The video for this transcript no longer exists"));
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before regenerating its transcript");
        }
        ProcessingJob active = activeJob(transcript);
        if (active != null) {
            throw new AppException(
                    HttpStatus.CONFLICT,
                    "Already regenerating — job #" + active.getId() + " is "
                            + active.getStatus().name().toLowerCase());
        }

        // Same language as the video → transcribe the audio; otherwise translate
        // from the spoken-language transcript.
        boolean isTranslation =
                video.getLanguage() != null && !video.getLanguage().equals(transcript.getLanguage());
        ProcessingJobType type = isTranslation ? ProcessingJobType.TRANSLATE : ProcessingJobType.TRANSCRIBE;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("transcriptId", transcript.getId());
        if (isTranslation) {
            params.put("sourceLanguage", video.getLanguage());
            params.put("targetLanguage", transcript.getLanguage());
        } else {
            params.put("language", transcript.getLanguage());
        }

        ProcessingJob job = processingJobService.enqueue(
                video.getId(),
                type,
                toJson(params, false),
                "Regeneration of transcript #" + transcript.getId() + " (" + transcript.getLanguage()
                        + ") requested by " + actingUsername);
        transcript.setLastJobId(job.getId());
        transcriptRepository.save(transcript);
        return processingJobService.getJob(job.getId());
    }

    @Override
    @Transactional
    public Long saveGenerated(
            Long videoId, String language, List<TranscriptSegmentDto> segments, Long jobId, String actor) {
        Transcript transcript = transcriptRepository
                .findByVideoIdAndLanguage(videoId, language)
                .orElseGet(() -> transcriptRepository.save(Transcript.builder()
                        .videoId(videoId)
                        .language(language)
                        .createdBy(actor)
                        .build()));
        transcript.setSource(TranscriptSource.AUTO);
        transcript.setUpdatedBy(actor);
        transcript.setLastJobId(jobId);
        replaceSegments(transcript, validateAndSort(segments));
        transcript = transcriptRepository.saveAndFlush(transcript);
        snapshot(transcript, jobId != null ? "Generated by job #" + jobId : "Generated", actor);
        return transcript.getId();
    }

    // ── Search / export ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TranscriptSearchHit> search(TranscriptSearchRequest request) {
        String q = request.getQ() == null ? "" : request.getQ().trim();
        if (q.length() < MIN_SEARCH_LENGTH) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Search for at least " + MIN_SEARCH_LENGTH + " characters");
        }
        String language =
                request.getLanguage() == null || request.getLanguage().isBlank() ? null : request.getLanguage();
        int size = Math.max(1, Math.min(request.getSize(), 100));
        Page<TranscriptSegmentRepository.SearchHit> hits = segmentRepository.search(
                "%" + escapeLike(q.toLowerCase()) + "%",
                request.getVideoId(),
                language,
                PageRequest.of(Math.max(request.getPage() - 1, 0), size));
        return PageResponse.of(hits.map(h -> TranscriptSearchHit.builder()
                .transcriptId(h.getTranscriptId())
                .videoId(h.getVideoId())
                .videoTitle(h.getVideoTitle())
                .language(h.getLanguage())
                .segmentId(h.getSegmentId())
                .position(h.getPosition())
                .startMs(h.getStartMs())
                .endMs(h.getEndMs())
                .text(h.getText())
                .build()));
    }

    @Override
    @Transactional(readOnly = true)
    public ExportedFile export(Long id, String format) {
        Transcript transcript = findTranscript(id);
        Video video = videoRepository.findById(transcript.getVideoId()).orElse(null);
        List<TranscriptSegment> segments = segmentRepository.findByTranscriptIdOrderByPositionAsc(id);
        String base = slug(video != null ? video.getTitle() : "video-" + transcript.getVideoId()) + "."
                + transcript.getLanguage();

        String fmt = format == null ? "srt" : format.toLowerCase();
        return switch (fmt) {
            case "srt" -> new ExportedFile(base + ".srt", "application/x-subrip; charset=utf-8", utf8(toSrt(segments)));
            case "vtt" -> new ExportedFile(base + ".vtt", "text/vtt; charset=utf-8", utf8(toVtt(segments)));
            case "txt" -> new ExportedFile(base + ".txt", "text/plain; charset=utf-8", utf8(toText(segments)));
            case "json" ->
                new ExportedFile(
                        base + ".json", "application/json", utf8(toJson(exportJson(transcript, video, segments))));
            default ->
                throw new AppException(
                        HttpStatus.BAD_REQUEST, "Unknown export format '" + format + "' — use srt, vtt, txt or json");
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Transcript findTranscript(Long id) {
        return transcriptRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Transcript not found with id: " + id));
    }

    private ProcessingJob activeJob(Transcript transcript) {
        if (transcript.getLastJobId() == null) {
            return null;
        }
        return jobRepository
                .findById(transcript.getLastJobId())
                .filter(j -> j.getStatus().isActive())
                .orElse(null);
    }

    // Bean validation covers each field; this covers the relationships between
    // them and reports the offending row number (1-based, input order).
    private List<TranscriptSegmentDto> validateAndSort(List<TranscriptSegmentDto> input) {
        List<TranscriptSegmentDto> segments = input == null ? List.of() : input;
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegmentDto s = segments.get(i);
            if (s.getEndMs() <= s.getStartMs()) {
                throw new AppException(
                        HttpStatus.BAD_REQUEST, "Segment " + (i + 1) + ": end time must be after start time");
            }
        }
        List<TranscriptSegmentDto> sorted = new ArrayList<>(segments);
        // Stable, so segments sharing a start keep the order they were given in.
        sorted.sort(Comparator.comparingLong(TranscriptSegmentDto::getStartMs));
        return sorted;
    }

    private void replaceSegments(Transcript transcript, List<TranscriptSegmentDto> segments) {
        segmentRepository.deleteByTranscriptId(transcript.getId());
        List<TranscriptSegment> rows = new ArrayList<>(segments.size());
        int words = 0;
        long end = 0;
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegmentDto s = segments.get(i);
            String text = s.getText().strip();
            rows.add(TranscriptSegment.builder()
                    .transcriptId(transcript.getId())
                    .position(i)
                    .startMs(s.getStartMs())
                    .endMs(s.getEndMs())
                    .text(text)
                    .speaker(
                            s.getSpeaker() == null || s.getSpeaker().isBlank()
                                    ? null
                                    : s.getSpeaker().strip())
                    .build());
            words += countWords(text);
            end = Math.max(end, s.getEndMs());
        }
        segmentRepository.saveAll(rows);
        transcript.setSegmentCount(rows.size());
        transcript.setWordCount(words);
        transcript.setDurationMs(end);
        // Segments live in another table: without touching this row, an edit
        // that keeps the same counts (fixing a typo) wouldn't bump @Version,
        // and the stale-edit check in updateTranscript would miss it.
        transcript.setUpdatedAt(LocalDateTime.now());
    }

    private void snapshot(Transcript transcript, String summary, String actor) {
        List<TranscriptSegmentDto> segments =
                segmentRepository.findByTranscriptIdOrderByPositionAsc(transcript.getId()).stream()
                        .map(s -> TranscriptSegmentDto.builder()
                                .startMs(s.getStartMs())
                                .endMs(s.getEndMs())
                                .text(s.getText())
                                .speaker(s.getSpeaker())
                                .build())
                        .toList();
        revisions.record(
                ContentRevision.EntityType.TRANSCRIPT,
                transcript.getId(),
                new RevisionService.TranscriptSnapshot(transcript.getLanguage(), segments),
                segments.size(),
                summary,
                actor);
    }

    static int countWords(String text) {
        Matcher m = WORD.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String toJson(Object value) {
        return toJson(value, true);
    }

    // Pretty for files people open (exports); compact for values stored in the DB.
    private String toJson(Object value, boolean pretty) {
        try {
            return (pretty ? objectMapper.writerWithDefaultPrettyPrinter() : objectMapper.writer())
                    .writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise to JSON", e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String slug(String title) {
        // \p{M} keeps combining marks — Khmer, Thai and Hindi vowel signs are marks.
        String s = title.toLowerCase().replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "transcript" : s;
    }

    // 01:02:03,456 (SRT) / 01:02:03.456 (VTT)
    static String timestamp(long ms, char fractionSeparator) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1000;
        long f = ms % 1000;
        return String.format("%02d:%02d:%02d%c%03d", h, m, s, fractionSeparator, f);
    }

    static String toSrt(List<TranscriptSegment> segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            out.append(i + 1)
                    .append('\n')
                    .append(timestamp(s.getStartMs(), ','))
                    .append(" --> ")
                    .append(timestamp(s.getEndMs(), ','))
                    .append('\n')
                    .append(s.getText())
                    .append("\n\n");
        }
        return out.toString();
    }

    static String toVtt(List<TranscriptSegment> segments) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (TranscriptSegment s : segments) {
            out.append(timestamp(s.getStartMs(), '.'))
                    .append(" --> ")
                    .append(timestamp(s.getEndMs(), '.'))
                    .append('\n');
            // WebVTT voice tag carries the speaker, if there is one.
            out.append(s.getSpeaker() != null ? "<v " + s.getSpeaker() + ">" : "")
                    .append(s.getText())
                    .append("\n\n");
        }
        return out.toString();
    }

    static String toText(List<TranscriptSegment> segments) {
        return segments.stream()
                        .map(s -> (s.getSpeaker() != null ? s.getSpeaker() + ": " : "") + s.getText())
                        .collect(Collectors.joining("\n"))
                + (segments.isEmpty() ? "" : "\n");
    }

    private static Map<String, Object> exportJson(Transcript t, Video video, List<TranscriptSegment> segments) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("transcriptId", t.getId());
        root.put("videoId", t.getVideoId());
        root.put("videoTitle", video != null ? video.getTitle() : null);
        root.put("language", t.getLanguage());
        root.put("source", t.getSource().name());
        root.put(
                "segments",
                segments.stream()
                        .map(s -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("start", s.getStartMs() / 1000.0);
                            m.put("end", s.getEndMs() / 1000.0);
                            m.put("text", s.getText());
                            if (s.getSpeaker() != null) {
                                m.put("speaker", s.getSpeaker());
                            }
                            return m;
                        })
                        .toList());
        return root;
    }

    private TranscriptResponse toDetailResponse(Transcript transcript) {
        Video video = videoRepository.findById(transcript.getVideoId()).orElse(null);
        ProcessingJob job = transcript.getLastJobId() == null
                ? null
                : jobRepository.findById(transcript.getLastJobId()).orElse(null);
        List<TranscriptSegmentDto> segments =
                segmentRepository.findByTranscriptIdOrderByPositionAsc(transcript.getId()).stream()
                        .map(s -> TranscriptSegmentDto.builder()
                                .id(s.getId())
                                .startMs(s.getStartMs())
                                .endMs(s.getEndMs())
                                .text(s.getText())
                                .speaker(s.getSpeaker())
                                .build())
                        .toList();
        return toResponse(transcript, video, job, segments);
    }

    private TranscriptResponse toResponse(
            Transcript t, Video video, ProcessingJob job, List<TranscriptSegmentDto> segments) {
        return TranscriptResponse.builder()
                .id(t.getId())
                .videoId(t.getVideoId())
                .videoTitle(video != null ? video.getTitle() : null)
                .videoLanguage(video != null ? video.getLanguage() : null)
                .videoDurationSeconds(video != null ? video.getDurationSeconds() : null)
                .videoUrl(video != null ? video.getVideoUrl() : null)
                .language(t.getLanguage())
                .source(t.getSource())
                .segmentCount(t.getSegmentCount())
                .wordCount(t.getWordCount())
                .durationMs(t.getDurationMs())
                .createdBy(t.getCreatedBy())
                .updatedBy(t.getUpdatedBy())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .version(t.getVersion())
                .lastJobId(t.getLastJobId())
                .lastJobStatus(job != null ? job.getStatus() : null)
                .lastJobProgress(job != null ? job.getProgress() : null)
                .segments(segments)
                .build();
    }
}
