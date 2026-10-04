package com.example.videolingo.service;

import com.example.videolingo.dto.CreateSubtitleRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.RegenerateSubtitleRequest;
import com.example.videolingo.dto.SubtitleFilterRequest;
import com.example.videolingo.dto.SubtitleResponse;
import com.example.videolingo.dto.UpdateSubtitleRequest;
import com.example.videolingo.entity.SubtitleKind;
import com.example.videolingo.revision.RevisionService;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface SubtitleService {

    PageResponse<SubtitleResponse> list(SubtitleFilterRequest filter);

    SubtitleResponse get(Long id);

    SubtitleResponse create(CreateSubtitleRequest request, String actingUsername);

    SubtitleResponse upload(
            MultipartFile file, Long videoId, String language, String label, SubtitleKind kind, String actingUsername);

    SubtitleResponse update(Long id, UpdateSubtitleRequest request, String actingUsername);

    SubtitleResponse setDefault(Long id, String actingUsername);

    // Rebuilds the cues from a transcript, replacing any manual edits.
    SubtitleResponse regenerate(Long id, RegenerateSubtitleRequest request, String actingUsername);

    void delete(Long id);

    TranscriptService.ExportedFile download(Long id, String format);

    // Version history — newest first.
    List<RevisionService.RevisionSummary> revisions(Long id);

    RevisionService.RevisionDetail<RevisionService.SubtitleSnapshot> revision(Long id, Long revisionId);

    // `version` (optional) guards against restoring over edits made since the page loaded.
    SubtitleResponse restoreRevision(Long id, Long revisionId, Long version, String actingUsername);
}
