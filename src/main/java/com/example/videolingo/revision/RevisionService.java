package com.example.videolingo.revision;

import com.example.videolingo.dto.SubtitleCueDto;
import com.example.videolingo.dto.SubtitleRulesDto;
import com.example.videolingo.dto.TranscriptSegmentDto;
import com.example.videolingo.entity.ContentRevision;
import com.example.videolingo.entity.ContentRevision.EntityType;
import com.example.videolingo.entity.SubtitleKind;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.ContentRevisionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Version history for subtitle tracks and transcripts. The owning services
// call record() after each save that changes content, inside their own
// transaction, so a failed save leaves no revision behind.
@Service
@RequiredArgsConstructor
public class RevisionService {

    /** Oldest revisions beyond this many per track/transcript are dropped. */
    static final int KEEP = 50;

    public record SubtitleSnapshot(
            String label, String language, SubtitleKind kind, SubtitleRulesDto rules, List<SubtitleCueDto> cues) {}

    public record TranscriptSnapshot(String language, List<TranscriptSegmentDto> segments) {}

    public record RevisionSummary(
            Long id, int number, String summary, int itemCount, String createdBy, LocalDateTime createdAt) {}

    public record RevisionDetail<T>(
            Long id,
            int number,
            String summary,
            int itemCount,
            String createdBy,
            LocalDateTime createdAt,
            T snapshot) {}

    private final ContentRevisionRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(EntityType type, Long entityId, Object snapshot, int itemCount, String summary, String actor) {
        int number = repository.maxNumber(type, entityId) + 1;
        repository.save(ContentRevision.builder()
                .entityType(type)
                .entityId(entityId)
                .number(number)
                .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                .itemCount(itemCount)
                .snapshot(toJson(snapshot))
                .createdBy(actor)
                .build());
        if (number > KEEP) {
            repository.deleteUpTo(type, entityId, number - KEEP);
        }
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> history(EntityType type, Long entityId) {
        return repository.history(type, entityId).stream()
                .map(r -> new RevisionSummary(
                        r.getId(), r.getNumber(), r.getSummary(), r.getItemCount(), r.getCreatedBy(), r.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public <T> RevisionDetail<T> get(EntityType type, Long entityId, Long revisionId, Class<T> snapshotType) {
        ContentRevision r = repository
                .findByIdAndEntityTypeAndEntityId(revisionId, type, entityId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Revision not found"));
        T snapshot;
        try {
            snapshot = objectMapper.readValue(r.getSnapshot(), snapshotType);
        } catch (JsonProcessingException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Revision " + r.getNumber() + " couldn't be read");
        }
        return new RevisionDetail<>(
                r.getId(),
                r.getNumber(),
                r.getSummary(),
                r.getItemCount(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                snapshot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAll(EntityType type, Long entityId) {
        repository.deleteAllOf(type, entityId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
