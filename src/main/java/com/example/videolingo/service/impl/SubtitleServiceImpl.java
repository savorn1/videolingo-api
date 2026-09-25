package com.example.videolingo.service.impl;

import com.example.videolingo.dto.CreateSubtitleRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.RegenerateSubtitleRequest;
import com.example.videolingo.dto.SubtitleCueDto;
import com.example.videolingo.dto.SubtitleFilterRequest;
import com.example.videolingo.dto.SubtitleIssueDto;
import com.example.videolingo.dto.SubtitleResponse;
import com.example.videolingo.dto.SubtitleRulesDto;
import com.example.videolingo.dto.UpdateSubtitleRequest;
import com.example.videolingo.entity.ContentRevision;
import com.example.videolingo.entity.ReviewStatus;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.SubtitleCue;
import com.example.videolingo.entity.SubtitleKind;
import com.example.videolingo.entity.SubtitleSource;
import com.example.videolingo.entity.Transcript;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.LanguageRepository;
import com.example.videolingo.repository.SubtitleCommentRepository;
import com.example.videolingo.repository.SubtitleCueRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.TranscriptSegmentRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.service.LanguageService;
import com.example.videolingo.service.SubtitleService;
import com.example.videolingo.service.TranscriptService;
import com.example.videolingo.revision.RevisionService;
import com.example.videolingo.subtitle.Cue;
import com.example.videolingo.subtitle.SubtitleFiles;
import com.example.videolingo.subtitle.SubtitleIssue;
import com.example.videolingo.subtitle.SubtitleQuality;
import com.example.videolingo.subtitle.SubtitleRules;
import com.example.videolingo.settings.SettingsService;
import com.example.videolingo.subtitle.SubtitleSegmenter;
import com.example.videolingo.util.PageableUtils;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubtitleServiceImpl implements SubtitleService {

    private static final Set<String> SORTABLE = Set.of("id", "label", "language", "kind", "source", "published", "cueCount", "issueCount", "durationMs", "createdAt", "updatedAt", "reviewStatus");

    private final SubtitleRepository subtitleRepository;
    private final SubtitleCueRepository cueRepository;
    private final VideoRepository videoRepository;
    private final TranscriptRepository transcriptRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final LanguageRepository languageRepository;
    private final LanguageService languageService;
    private final SettingsService settings;
    private final RevisionService revisions;
    private final SubtitleCommentRepository commentRepository;

    // ── Read ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SubtitleResponse> list(SubtitleFilterRequest filter) {
        List<Specification<Subtitle>> conditions = new ArrayList<>();
        if (filter.getSearch() != null && !filter.getSearch().isBlank()) {
            String pattern = "%" + TranscriptServiceImpl.escapeLike(filter.getSearch().trim().toLowerCase()) + "%";
            conditions.add((root, query, cb) -> {
                Subquery<Long> videoIds = query.subquery(Long.class);
                var video = videoIds.from(Video.class);
                videoIds.select(video.get("id")).where(cb.like(cb.lower(video.get("title")), pattern, '\\'));
                return cb.or(root.get("videoId").in(videoIds), cb.like(cb.lower(root.get("label")), pattern, '\\'));
            });
        }
        if (filter.getVideoId() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("videoId"), filter.getVideoId()));
        }
        if (filter.getVideoIds() != null && !filter.getVideoIds().isEmpty()) {
            conditions.add((root, query, cb) -> root.get("videoId").in(filter.getVideoIds()));
        }
        if (filter.getLanguage() != null && !filter.getLanguage().isBlank()) {
            conditions.add((root, query, cb) -> cb.equal(cb.lower(root.get("language")), filter.getLanguage().toLowerCase()));
        }
        if (filter.getSource() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("source"), filter.getSource()));
        }
        if (filter.getPublished() != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("published"), filter.getPublished()));
        }
        if (filter.getReviewStatus() != null) {
            // Rows from before the review workflow have no status: they're drafts.
            conditions.add(filter.getReviewStatus() == ReviewStatus.DRAFT
                    ? (root, query, cb) -> cb.or(cb.isNull(root.get("reviewStatus")), cb.equal(root.get("reviewStatus"), ReviewStatus.DRAFT))
                    : (root, query, cb) -> cb.equal(root.get("reviewStatus"), filter.getReviewStatus()));
        }
        if (filter.getHasIssues() != null) {
            conditions.add(filter.getHasIssues()
                    ? (root, query, cb) -> cb.greaterThan(root.get("issueCount"), 0)
                    : (root, query, cb) -> cb.equal(root.get("issueCount"), 0));
        }
        String sortBy = SORTABLE.contains(filter.getSortBy()) ? filter.getSortBy() : "updatedAt";
        Page<Subtitle> page = subtitleRepository.findAll(Specification.allOf(conditions),
                PageableUtils.of(filter.getPage(), filter.getSize(), sortBy, filter.getSortOrder()));
        Map<Long, Video> videos = videoRepository.findAllById(page.getContent().stream().map(Subtitle::getVideoId).distinct().toList())
                .stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        return PageResponse.of(page.map(s -> toResponse(s, videos.get(s.getVideoId()), null, null)));
    }

    @Override
    @Transactional(readOnly = true)
    public SubtitleResponse get(Long id) {
        return toDetailResponse(find(id), null);
    }

    // ── Create / upload ───────────────────────────────────────────────────

    @Override
    @Transactional
    public SubtitleResponse create(CreateSubtitleRequest request, String actingUsername) {
        Video video = requireLiveVideo(request.getVideoId());
        Transcript transcript = null;
        String language;
        if (request.getTranscriptId() != null) {
            transcript = requireTranscriptOf(request.getTranscriptId(), video.getId());
            language = transcript.getLanguage();
        } else {
            language = languageService.resolve(request.getLanguage(), true);
        }
        SubtitleKind kind = request.getKind() == null ? SubtitleKind.SUBTITLES : request.getKind();
        SubtitleRules rules = request.getRules() != null ? toRules(request.getRules()) : settings.subtitleDefaults(language);

        Subtitle subtitle = Subtitle.builder()
                .videoId(video.getId())
                .language(language)
                .label(chooseLabel(video.getId(), request.getLabel(), language, kind, null))
                .kind(kind)
                .source(transcript != null ? SubtitleSource.GENERATED : SubtitleSource.MANUAL)
                .transcriptId(transcript != null ? transcript.getId() : null)
                .createdBy(actingUsername)
                .updatedBy(actingUsername)
                .build();
        applyRules(subtitle, rules);
        subtitle = subtitleRepository.save(subtitle);
        replaceCues(subtitle, transcript != null ? generateFrom(transcript, rules) : List.of());
        subtitle = subtitleRepository.save(subtitle);
        snapshot(subtitle, transcript != null ? "Generated from transcript #" + transcript.getId() : "Created", actingUsername);
        return toDetailResponse(subtitle, null);
    }

    @Override
    @Transactional
    public SubtitleResponse upload(MultipartFile file, Long videoId, String language, String label, SubtitleKind kind, String actingUsername) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose a .srt or .vtt file to upload");
        }
        int maxMb = settings.storage().maxSubtitleUploadMb();
        if (file.getSize() > maxMb * 1024L * 1024L) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Subtitle files can be at most " + maxMb + " MB");
        }
        Video video = requireLiveVideo(videoId);
        String resolvedLanguage = languageService.resolve(language, true);

        SubtitleFiles.Parsed parsed;
        try {
            parsed = SubtitleFiles.parse(decodeUtf8(file.getBytes()));
        } catch (IllegalArgumentException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file");
        }
        if (parsed.cues().isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "No usable cues in that file"
                    + (parsed.warnings().isEmpty() ? "" : " — " + String.join("; ", parsed.warnings())));
        }

        SubtitleKind k = kind == null ? SubtitleKind.SUBTITLES : kind;
        Subtitle subtitle = Subtitle.builder()
                .videoId(video.getId())
                .language(resolvedLanguage)
                .label(chooseLabel(video.getId(), label, resolvedLanguage, k, null))
                .kind(k)
                .source(SubtitleSource.UPLOADED)
                .originalFilename(file.getOriginalFilename())
                .createdBy(actingUsername)
                .updatedBy(actingUsername)
                .build();
        applyRules(subtitle, settings.subtitleDefaults(resolvedLanguage));
        subtitle = subtitleRepository.save(subtitle);
        replaceCues(subtitle, parsed.cues());
        subtitle = subtitleRepository.save(subtitle);
        snapshot(subtitle, "Uploaded " + (file.getOriginalFilename() == null ? "a file" : file.getOriginalFilename()), actingUsername);
        return toDetailResponse(subtitle, parsed.warnings());
    }

    // ── Update / default / regenerate / delete ────────────────────────────

    @Override
    @Transactional
    public SubtitleResponse update(Long id, UpdateSubtitleRequest request, String actingUsername) {
        Subtitle subtitle = find(id);
        if (!Objects.equals(subtitle.getVersion(), request.getVersion())) {
            throw new AppException(HttpStatus.CONFLICT, "This subtitle track changed since you opened it. Reload it, then re-apply your edits.");
        }
        boolean wasPublished = subtitle.isPublished();
        SubtitleRules rulesBefore = rulesOf(subtitle);
        boolean languageUnchanged = request.getLanguage().strip().equalsIgnoreCase(subtitle.getLanguage());
        subtitle.setLanguage(languageService.resolve(request.getLanguage(), !languageUnchanged));
        subtitle.setLabel(chooseLabel(subtitle.getVideoId(), request.getLabel(), subtitle.getLanguage(), request.getKind(), subtitle.getId()));
        subtitle.setKind(request.getKind());
        subtitle.setPublished(request.getPublished());
        if (!subtitle.isPublished()) {
            // An unpublished track can't be what learners get by default.
            subtitle.setDefault(false);
        }
        applyRules(subtitle, toRules(request.getRules()));
        subtitle.setUpdatedBy(actingUsername);

        if (request.getCues() != null) {
            List<Cue> cues = new ArrayList<>();
            for (int i = 0; i < request.getCues().size(); i++) {
                SubtitleCueDto c = request.getCues().get(i);
                if (c.getEndMs() <= c.getStartMs()) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "Cue " + (i + 1) + ": end time must be after start time");
                }
                cues.add(new Cue(c.getStartMs(), c.getEndMs(), normalizeCueText(c.getText())));
            }
            cues.sort(Comparator.comparingLong(Cue::startMs));
            replaceCues(subtitle, cues);
            subtitle.setSource(SubtitleSource.MANUAL);
        } else {
            // Rules may have changed, so the warnings may have too.
            recountIssues(subtitle);
        }
        boolean contentChanged = request.getCues() != null || !rulesBefore.equals(rulesOf(subtitle));
        if (contentChanged) {
            reopenIfApproved(subtitle);
        }
        if (subtitle.isPublished() && !wasPublished && settings.translation().requireApprovalToPublish()
                && subtitle.reviewStatus() != ReviewStatus.APPROVED) {
            throw new AppException(HttpStatus.CONFLICT, "Get this track approved before publishing it (Settings › Translation requires review first)");
        }
        if (subtitle.isPublished() && subtitle.getIssueCount() > 0 && settings.translation().blockPublishWithIssues()) {
            throw new AppException(HttpStatus.CONFLICT, "Fix the " + subtitle.getIssueCount() + " readability issue"
                    + (subtitle.getIssueCount() == 1 ? "" : "s") + " before publishing (Settings › Translation doesn't allow publishing tracks with issues)");
        }
        subtitle = subtitleRepository.saveAndFlush(subtitle);
        if (contentChanged) {
            snapshot(subtitle, request.getCues() != null ? "Edited" : "Changed the readability rules", actingUsername);
        }
        return toDetailResponse(subtitle, null);
    }

    @Override
    @Transactional
    public SubtitleResponse setDefault(Long id, String actingUsername) {
        Subtitle subtitle = find(id);
        if (!subtitle.isPublished()) {
            throw new AppException(HttpStatus.CONFLICT, "Publish this track before making it the default");
        }
        if (!subtitle.isDefault()) {
            subtitleRepository.clearDefaultForVideo(subtitle.getVideoId());
            subtitle = find(id);
            subtitle.setDefault(true);
            subtitle.setUpdatedBy(actingUsername);
            subtitle = subtitleRepository.save(subtitle);
        }
        return toDetailResponse(subtitle, null);
    }

    @Override
    @Transactional
    public SubtitleResponse regenerate(Long id, RegenerateSubtitleRequest request, String actingUsername) {
        Subtitle subtitle = find(id);
        Long transcriptId = request != null && request.getTranscriptId() != null ? request.getTranscriptId() : subtitle.getTranscriptId();
        if (transcriptId == null) {
            throw new AppException(HttpStatus.CONFLICT, "This track wasn't generated from a transcript — choose one to regenerate from");
        }
        Transcript transcript = requireTranscriptOf(transcriptId, subtitle.getVideoId());
        if (!transcript.getLanguage().equalsIgnoreCase(subtitle.getLanguage())) {
            throw new AppException(HttpStatus.CONFLICT, "That transcript is in " + transcript.getLanguage() + " but this track is " + subtitle.getLanguage()
                    + " — pick a " + subtitle.getLanguage() + " transcript, or create a new track");
        }
        SubtitleRules rules = request != null && request.getRules() != null ? toRules(request.getRules()) : rulesOf(subtitle);
        applyRules(subtitle, rules);
        replaceCues(subtitle, generateFrom(transcript, rules));
        subtitle.setTranscriptId(transcript.getId());
        subtitle.setSource(SubtitleSource.GENERATED);
        subtitle.setUpdatedBy(actingUsername);
        reopenIfApproved(subtitle);
        subtitle = subtitleRepository.saveAndFlush(subtitle);
        snapshot(subtitle, "Regenerated from transcript #" + transcript.getId(), actingUsername);
        return toDetailResponse(subtitle, null);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Subtitle subtitle = find(id);
        cueRepository.deleteBySubtitleId(id);
        commentRepository.deleteBySubtitleId(id);
        revisions.deleteAll(ContentRevision.EntityType.SUBTITLE, id);
        subtitleRepository.delete(subtitle);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionService.RevisionSummary> revisions(Long id) {
        find(id);
        return revisions.history(ContentRevision.EntityType.SUBTITLE, id);
    }

    @Override
    @Transactional(readOnly = true)
    public RevisionService.RevisionDetail<RevisionService.SubtitleSnapshot> revision(Long id, Long revisionId) {
        find(id);
        return revisions.get(ContentRevision.EntityType.SUBTITLE, id, revisionId, RevisionService.SubtitleSnapshot.class);
    }

    // Brings back that revision's cues and readability rules as a new save
    // (so the restore itself can be undone). Label, language and kind stay:
    // restoring an old label could clash with another track's.
    @Override
    @Transactional
    public SubtitleResponse restoreRevision(Long id, Long revisionId, Long version, String actingUsername) {
        Subtitle subtitle = find(id);
        if (version != null && !Objects.equals(subtitle.getVersion(), version)) {
            throw new AppException(HttpStatus.CONFLICT, "This subtitle track changed since you opened it. Reload it, then try again.");
        }
        var revision = revisions.get(ContentRevision.EntityType.SUBTITLE, id, revisionId, RevisionService.SubtitleSnapshot.class);
        RevisionService.SubtitleSnapshot snap = revision.snapshot();
        if (snap.rules() != null) {
            applyRules(subtitle, toRules(snap.rules()));
        }
        List<Cue> cues = snap.cues() == null ? List.of() : snap.cues().stream()
                .map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText())).toList();
        replaceCues(subtitle, cues);
        subtitle.setSource(SubtitleSource.MANUAL);
        subtitle.setUpdatedBy(actingUsername);
        reopenIfApproved(subtitle);
        subtitle = subtitleRepository.saveAndFlush(subtitle);
        snapshot(subtitle, "Restored revision " + revision.number(), actingUsername);
        return toDetailResponse(subtitle, null);
    }

    @Override
    @Transactional(readOnly = true)
    public TranscriptService.ExportedFile download(Long id, String format) {
        Subtitle subtitle = find(id);
        Video video = videoRepository.findById(subtitle.getVideoId()).orElse(null);
        List<Cue> cues = cuesOf(subtitle.getId());
        String base = TranscriptServiceImpl.slug(video != null ? video.getTitle() : "video-" + subtitle.getVideoId())
                + "." + TranscriptServiceImpl.slug(subtitle.getLabel()) + "." + subtitle.getLanguage();
        String fmt = format == null ? "vtt" : format.toLowerCase();
        return switch (fmt) {
            case "srt" -> new TranscriptService.ExportedFile(base + ".srt", "application/x-subrip; charset=utf-8",
                    SubtitleFiles.toSrt(cues).getBytes(StandardCharsets.UTF_8));
            case "vtt" -> new TranscriptService.ExportedFile(base + ".vtt", "text/vtt; charset=utf-8",
                    SubtitleFiles.toVtt(cues).getBytes(StandardCharsets.UTF_8));
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "Unknown format '" + format + "' — use srt or vtt");
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────

    // An approval covers the content that was reviewed; once that changes the
    // track needs reviewing again. IN_REVIEW stays put — reviewers often fix
    // small things themselves before approving.
    private static void reopenIfApproved(Subtitle subtitle) {
        if (subtitle.reviewStatus() == ReviewStatus.APPROVED) {
            subtitle.setReviewStatus(ReviewStatus.DRAFT);
        }
    }

    private void snapshot(Subtitle subtitle, String summary, String actor) {
        List<SubtitleCueDto> cues = cueRepository.findBySubtitleIdOrderByPositionAsc(subtitle.getId()).stream()
                .map(c -> SubtitleCueDto.builder().startMs(c.getStartMs()).endMs(c.getEndMs()).text(c.getText()).build())
                .toList();
        revisions.record(ContentRevision.EntityType.SUBTITLE, subtitle.getId(),
                new RevisionService.SubtitleSnapshot(subtitle.getLabel(), subtitle.getLanguage(), subtitle.getKind(), toRulesDto(subtitle), cues),
                cues.size(), summary, actor);
    }

    private Subtitle find(Long id) {
        return subtitleRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Subtitle track not found with id: " + id));
    }

    private Video requireLiveVideo(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Video not found with id: " + videoId));
        if (video.isDeleted()) {
            throw new AppException(HttpStatus.CONFLICT, "Restore the video before adding subtitles to it");
        }
        return video;
    }

    private Transcript requireTranscriptOf(Long transcriptId, Long videoId) {
        Transcript transcript = transcriptRepository.findById(transcriptId)
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Transcript not found with id: " + transcriptId));
        if (!transcript.getVideoId().equals(videoId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Transcript #" + transcriptId + " belongs to a different video");
        }
        if (transcript.getSegmentCount() == 0) {
            throw new AppException(HttpStatus.CONFLICT, "Transcript #" + transcriptId + " is empty — there's nothing to build subtitles from");
        }
        return transcript;
    }

    private List<Cue> generateFrom(Transcript transcript, SubtitleRules rules) {
        List<Cue> segments = segmentRepository.findByTranscriptIdOrderByPositionAsc(transcript.getId()).stream()
                .map(s -> new Cue(s.getStartMs(), s.getEndMs(), s.getText()))
                .toList();
        return SubtitleSegmenter.segment(segments, rules);
    }

    // Default labels ("Japanese", "Japanese (captions)") get " 2", " 3"… to
    // stay unique on the video; a label the admin typed must already be unique.
    private String chooseLabel(Long videoId, String requested, String language, SubtitleKind kind, Long selfId) {
        if (requested != null && !requested.isBlank()) {
            String label = requested.strip();
            boolean taken = selfId == null
                    ? subtitleRepository.existsByVideoIdAndLabelIgnoreCase(videoId, label)
                    : subtitleRepository.existsByVideoIdAndLabelIgnoreCaseAndIdNot(videoId, label, selfId);
            if (taken) {
                throw new AppException(HttpStatus.CONFLICT, "This video already has a track labelled \"" + label + "\"");
            }
            return label;
        }
        String base = languageRepository.findByCodeIgnoreCase(language).map(l -> l.getName()).orElse(language)
                + (kind == SubtitleKind.CAPTIONS ? " (captions)" : "");
        String label = base;
        for (int n = 2; subtitleRepository.existsByVideoIdAndLabelIgnoreCase(videoId, label); n++) {
            label = base + " " + n;
        }
        return label;
    }

    // Trim each line, drop blank lines, keep the author's line breaks.
    private static String normalizeCueText(String text) {
        return text.replace("\r\n", "\n").lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));
    }

    private void replaceCues(Subtitle subtitle, List<Cue> cues) {
        cueRepository.deleteBySubtitleId(subtitle.getId());
        List<SubtitleCue> rows = new ArrayList<>(cues.size());
        long end = 0;
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            rows.add(SubtitleCue.builder().subtitleId(subtitle.getId()).position(i).startMs(c.startMs()).endMs(c.endMs()).text(c.text()).build());
            end = Math.max(end, c.endMs());
        }
        cueRepository.saveAll(rows);
        subtitle.setCueCount(rows.size());
        subtitle.setDurationMs(end);
        subtitle.setIssueCount(SubtitleQuality.check(cues, rulesOf(subtitle)).size());
        // Cues live in another table, so replacing them doesn't dirty this row by
        // itself — and an unchanged row keeps its @Version, which would let a
        // stale editor overwrite freshly regenerated cues. Touching updatedAt
        // guarantees the version moves whenever the cues do.
        subtitle.setUpdatedAt(LocalDateTime.now());
    }

    private void recountIssues(Subtitle subtitle) {
        subtitle.setIssueCount(SubtitleQuality.check(cuesOf(subtitle.getId()), rulesOf(subtitle)).size());
    }

    private List<Cue> cuesOf(Long subtitleId) {
        return cueRepository.findBySubtitleIdOrderByPositionAsc(subtitleId).stream()
                .map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText())).toList();
    }

    private static SubtitleRules toRules(SubtitleRulesDto dto) {
        try {
            return new SubtitleRules(dto.getMaxCharsPerLine(), dto.getMaxLines(), dto.getMinDurationMs(), dto.getMaxDurationMs(), dto.getMaxCps());
        } catch (IllegalArgumentException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Maximum duration must be longer than the minimum");
        }
    }

    private static SubtitleRules rulesOf(Subtitle s) {
        return new SubtitleRules(s.getMaxCharsPerLine(), s.getMaxLines(), s.getMinDurationMs(), s.getMaxDurationMs(), s.getMaxCps());
    }

    private static void applyRules(Subtitle s, SubtitleRules r) {
        s.setMaxCharsPerLine(r.maxCharsPerLine());
        s.setMaxLines(r.maxLines());
        s.setMinDurationMs(r.minDurationMs());
        s.setMaxDurationMs(r.maxDurationMs());
        s.setMaxCps(r.maxCps());
    }

    private static SubtitleRulesDto toRulesDto(Subtitle s) {
        return SubtitleRulesDto.builder().maxCharsPerLine(s.getMaxCharsPerLine()).maxLines(s.getMaxLines())
                .minDurationMs(s.getMinDurationMs()).maxDurationMs(s.getMaxDurationMs()).maxCps(s.getMaxCps()).build();
    }

    // Strict UTF-8, so a Latin-1/Windows-1252 file is reported instead of
    // silently turning its accents into '?'.
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The file isn't UTF-8 text — re-save it as UTF-8 and upload again");
        }
    }

    private SubtitleResponse toDetailResponse(Subtitle subtitle, List<String> warnings) {
        Video video = videoRepository.findById(subtitle.getVideoId()).orElse(null);
        List<SubtitleCue> rows = cueRepository.findBySubtitleIdOrderByPositionAsc(subtitle.getId());
        List<Cue> cues = rows.stream().map(c -> new Cue(c.getStartMs(), c.getEndMs(), c.getText())).toList();
        List<SubtitleIssueDto> issues = SubtitleQuality.check(cues, rulesOf(subtitle)).stream()
                .map((SubtitleIssue i) -> new SubtitleIssueDto(i.cueIndex(), i.type().name(), i.message())).toList();
        List<SubtitleCueDto> cueDtos = rows.stream()
                .map(c -> SubtitleCueDto.builder().id(c.getId()).startMs(c.getStartMs()).endMs(c.getEndMs()).text(c.getText()).build())
                .toList();
        SubtitleResponse response = toResponse(subtitle, video, cueDtos, issues);
        response.setWarnings(warnings);
        response.setOpenComments(commentRepository.countBySubtitleIdAndResolvedFalse(subtitle.getId()));
        return response;
    }

    private SubtitleResponse toResponse(Subtitle s, Video video, List<SubtitleCueDto> cues, List<SubtitleIssueDto> issues) {
        String transcriptLanguage = s.getTranscriptId() == null ? null
                : transcriptRepository.findById(s.getTranscriptId()).map(Transcript::getLanguage).orElse(null);
        return SubtitleResponse.builder()
                .id(s.getId())
                .videoId(s.getVideoId())
                .videoTitle(video != null ? video.getTitle() : null)
                .videoUrl(video != null ? video.getVideoUrl() : null)
                .language(s.getLanguage())
                .label(s.getLabel())
                .kind(s.getKind())
                .source(s.getSource())
                .transcriptId(s.getTranscriptId())
                .transcriptLanguage(transcriptLanguage)
                .published(s.isPublished())
                .isDefault(s.isDefault())
                .rules(toRulesDto(s))
                .cueCount(s.getCueCount())
                .issueCount(s.getIssueCount())
                .durationMs(s.getDurationMs())
                .originalFilename(s.getOriginalFilename())
                .createdBy(s.getCreatedBy())
                .updatedBy(s.getUpdatedBy())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .version(s.getVersion())
                .reviewStatus(s.reviewStatus())
                .reviewRequestedBy(s.getReviewRequestedBy())
                .reviewRequestedAt(s.getReviewRequestedAt())
                .reviewedBy(s.getReviewedBy())
                .reviewedAt(s.getReviewedAt())
                .reviewNote(s.getReviewNote())
                .cues(cues)
                .issues(issues)
                .build();
    }
}
