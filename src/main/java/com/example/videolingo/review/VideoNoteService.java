package com.example.videolingo.review;

import com.example.videolingo.entity.User;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoNote;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.notification.NotificationService;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.repository.VideoNoteRepository;
import com.example.videolingo.repository.VideoRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Review notes on a video, between the people who work on it. Anyone who can edit videos can write,
// resolve and reopen notes; only a note's author or an admin can delete it. The video's owner hears
// about notes others write on it.
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoNoteService {

    /** atMs: the moment of the video the note is about; null = the whole video. */
    public record NoteRequest(@NotBlank @Size(max = 2000) String body, Long atMs) {}

    public record ResolveRequest(boolean resolved) {}

    public record NoteResponse(
            Long id,
            Long videoId,
            String body,
            Long atMs,
            String author,
            boolean resolved,
            String resolvedBy,
            LocalDateTime resolvedAt,
            LocalDateTime createdAt) {}

    private final VideoNoteRepository noteRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    @Transactional(readOnly = true)
    public List<NoteResponse> notes(Long videoId) {
        findVideo(videoId);
        return noteRepository.findByVideoIdOrderByCreatedAtAsc(videoId).stream()
                .map(VideoNoteService::toResponse)
                .toList();
    }

    @Transactional
    public NoteResponse addNote(Long videoId, NoteRequest request, String actor) {
        Video video = findVideo(videoId);
        if (request.atMs() != null && request.atMs() < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The note's time can't be before the start");
        }
        VideoNote note = noteRepository.save(VideoNote.builder()
                .videoId(videoId)
                .body(request.body().strip())
                .atMs(request.atMs())
                .author(actor)
                .build());
        String owner = video.getOwnerId() == null
                ? null
                : userRepository
                        .findById(video.getOwnerId())
                        .map(User::getUsername)
                        .orElse(null);
        if (owner != null && !owner.equals(actor)) {
            try {
                notifications.notifyInApp(
                        List.of(owner), "New note on “" + video.getTitle() + "”", actor + ": " + note.getBody(), actor);
            } catch (RuntimeException e) {
                // A notification problem must never lose the note itself.
                log.warn("Couldn't send a video note notification", e);
            }
        }
        return toResponse(note);
    }

    @Transactional
    public NoteResponse resolveNote(Long videoId, Long noteId, boolean resolved, String actor) {
        VideoNote note = findNote(videoId, noteId);
        note.setResolved(resolved);
        note.setResolvedBy(resolved ? actor : null);
        note.setResolvedAt(resolved ? LocalDateTime.now() : null);
        return toResponse(noteRepository.save(note));
    }

    @Transactional
    public void deleteNote(Long videoId, Long noteId, String actor, boolean isAdmin) {
        VideoNote note = findNote(videoId, noteId);
        if (!isAdmin && !Objects.equals(note.getAuthor(), actor)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Only the author or an admin can delete this note");
        }
        noteRepository.delete(note);
    }

    private Video findVideo(Long id) {
        return videoRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Video not found with id: " + id));
    }

    private VideoNote findNote(Long videoId, Long noteId) {
        return noteRepository
                .findByIdAndVideoId(noteId, videoId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Note not found"));
    }

    private static NoteResponse toResponse(VideoNote n) {
        return new NoteResponse(
                n.getId(),
                n.getVideoId(),
                n.getBody(),
                n.getAtMs(),
                n.getAuthor(),
                n.isResolved(),
                n.getResolvedBy(),
                n.getResolvedAt(),
                n.getCreatedAt());
    }
}
