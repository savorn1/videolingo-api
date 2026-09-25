package com.example.videolingo.service;

import com.example.videolingo.dto.TranscriptSegmentDto;
import java.util.List;

import com.example.videolingo.dto.CreateTranscriptRequest;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.dto.ProcessingJobResponse;
import com.example.videolingo.dto.TranscriptFilterRequest;
import com.example.videolingo.dto.TranscriptResponse;
import com.example.videolingo.dto.TranscriptSearchHit;
import com.example.videolingo.dto.TranscriptSearchRequest;
import com.example.videolingo.dto.UpdateTranscriptRequest;
import com.example.videolingo.revision.RevisionService;

public interface TranscriptService {

    PageResponse<TranscriptResponse> listTranscripts(TranscriptFilterRequest filter);

    TranscriptResponse getTranscript(Long id);

    TranscriptResponse createTranscript(CreateTranscriptRequest request, String actingUsername);

    TranscriptResponse updateTranscript(Long id, UpdateTranscriptRequest request, String actingUsername);

    void deleteTranscript(Long id);

    // Queues a TRANSCRIBE (spoken language) or TRANSLATE (any other language)
    // job that will replace this transcript's segments when it finishes.
    ProcessingJobResponse regenerate(Long id, String actingUsername);

    // Writes a processing job's output: replaces the segments of the video's
    // transcript in `language` (creating it if missing) and marks it AUTO.
    // Returns the transcript id. Used by the job worker, not the API.
    Long saveGenerated(Long videoId, String language, List<TranscriptSegmentDto> segments, Long jobId, String actor);

    PageResponse<TranscriptSearchHit> search(TranscriptSearchRequest request);

    ExportedFile export(Long id, String format);

    // Version history — newest first.
    List<RevisionService.RevisionSummary> revisions(Long id);

    RevisionService.RevisionDetail<RevisionService.TranscriptSnapshot> revision(Long id, Long revisionId);

    // `version` (optional) guards against restoring over edits made since the page loaded.
    TranscriptResponse restoreRevision(Long id, Long revisionId, Long version, String actingUsername);

    record ExportedFile(String filename, String contentType, byte[] content) {
    }
}
