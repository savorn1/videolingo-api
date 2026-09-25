package com.example.videolingo.review;

import com.example.videolingo.entity.ReviewStatus;
import com.example.videolingo.entity.Subtitle;
import com.example.videolingo.entity.SubtitleComment;
import com.example.videolingo.entity.SubtitleCue;
import com.example.videolingo.entity.Video;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.notification.NotificationService;
import com.example.videolingo.repository.SubtitleCommentRepository;
import com.example.videolingo.repository.SubtitleCueRepository;
import com.example.videolingo.repository.SubtitleRepository;
import com.example.videolingo.repository.VideoRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

// Subtitle review: submit → approve / request changes, plus comments pinned
// to moments in the track. Status rules are in ReviewStatus. Decisions notify
// the submitter in-app, submissions notify admins, and every transition
// publishes a SubtitleReviewEvent (webhooks listen for it).
@Service
@RequiredArgsConstructor
@Slf4j
public class SubtitleReviewService {

    public record ReviewRequest(@Size(max = 1000) String note) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String body, Long atMs) {
    }

    public record ResolveRequest(boolean resolved) {
    }

    public record CommentResponse(Long id, Long subtitleId, Long atMs, String cueText, String body, String author, boolean resolved,
                                  String resolvedBy, LocalDateTime resolvedAt, LocalDateTime createdAt) {
    }

    /** Published after the transition commits' transaction work is done (listeners use AFTER_COMMIT). */
    public record SubtitleReviewEvent(Long subtitleId, Long videoId, String label, String language, ReviewStatus status,
                                      String actor, String note) {
    }

    private final SubtitleRepository subtitleRepository;
    private final SubtitleCueRepository cueRepository;
    private final SubtitleCommentRepository commentRepository;
    private final VideoRepository videoRepository;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    // ── Transitions ───────────────────────────────────────────────────────

    @Transactional
    public void submit(Long id, String note, String actor) {
        Subtitle s = find(id);
        ReviewStatus status = s.reviewStatus();
        if (status == ReviewStatus.IN_REVIEW) {
            throw new AppException(HttpStatus.CONFLICT, "This track is already waiting for review");
        }
        if (status == ReviewStatus.APPROVED) {
            throw new AppException(HttpStatus.CONFLICT, "This track is already approved — edit it to start a new review");
        }
        if (s.getCueCount() == 0) {
            throw new AppException(HttpStatus.CONFLICT, "Add some cues before sending the track for review");
        }
        s.setReviewStatus(ReviewStatus.IN_REVIEW);
        s.setReviewRequestedBy(actor);
        s.setReviewRequestedAt(LocalDateTime.now());
        s.setReviewedBy(null);
        s.setReviewedAt(null);
        s.setReviewNote(blankToNull(note));
        subtitleRepository.save(s);
        String what = describe(s);
        notifyQuietly(() -> notifications.notifyAdmins("Subtitle ready for review: " + what,
                actor + " sent " + what + " for review." + (s.getReviewNote() != null ? "\n\nNote: " + s.getReviewNote() : ""), actor));
        publish(s, actor);
    }

    @Transactional
    public void approve(Long id, String note, String actor, boolean isAdmin) {
        Subtitle s = requireInReview(id);
        if (!isAdmin && actor.equals(s.getReviewRequestedBy())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Someone other than the submitter has to approve this track");
        }
        decide(s, ReviewStatus.APPROVED, note, actor);
        String what = describe(s);
        notifyQuietly(() -> notifications.notifyInApp(List.of(s.getReviewRequestedBy()), "Approved: " + what,
                actor + " approved " + what + "." + (s.getReviewNote() != null ? "\n\n" + s.getReviewNote() : ""), actor));
        publish(s, actor);
    }

    @Transactional
    public void requestChanges(Long id, String note, String actor) {
        if (note == null || note.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Say what needs to change");
        }
        Subtitle s = requireInReview(id);
        decide(s, ReviewStatus.CHANGES_REQUESTED, note, actor);
        String what = describe(s);
        notifyQuietly(() -> notifications.notifyInApp(List.of(s.getReviewRequestedBy()), "Changes requested: " + what,
                actor + " asked for changes to " + what + ":\n\n" + s.getReviewNote(), actor));
        publish(s, actor);
    }

    // ── Comments ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(Long subtitleId) {
        find(subtitleId);
        return commentRepository.findBySubtitleIdOrderByCreatedAtAsc(subtitleId).stream().map(SubtitleReviewService::toResponse).toList();
    }

    @Transactional
    public CommentResponse addComment(Long subtitleId, CommentRequest request, String actor) {
        Subtitle s = find(subtitleId);
        Long atMs = request.atMs();
        if (atMs != null && atMs < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The comment's time can't be negative");
        }
        String cueText = atMs == null ? null : cueAt(s.getId(), atMs);
        SubtitleComment c = commentRepository.save(SubtitleComment.builder()
                .subtitleId(s.getId())
                .atMs(atMs)
                .cueText(cueText == null ? null : cueText.length() > 500 ? cueText.substring(0, 500) : cueText)
                .body(request.body().strip())
                .author(actor)
                .build());
        // Let the submitter know while their track is in review (and not about their own comments).
        if (s.reviewStatus() == ReviewStatus.IN_REVIEW && s.getReviewRequestedBy() != null && !s.getReviewRequestedBy().equals(actor)) {
            String what = describe(s);
            notifyQuietly(() -> notifications.notifyInApp(List.of(s.getReviewRequestedBy()), "New comment on " + what,
                    actor + ": " + c.getBody(), actor));
        }
        return toResponse(c);
    }

    @Transactional
    public CommentResponse resolveComment(Long subtitleId, Long commentId, boolean resolved, String actor) {
        SubtitleComment c = findComment(subtitleId, commentId);
        c.setResolved(resolved);
        c.setResolvedBy(resolved ? actor : null);
        c.setResolvedAt(resolved ? LocalDateTime.now() : null);
        return toResponse(commentRepository.save(c));
    }

    @Transactional
    public void deleteComment(Long subtitleId, Long commentId, String actor, boolean isAdmin) {
        SubtitleComment c = findComment(subtitleId, commentId);
        if (!isAdmin && !Objects.equals(c.getAuthor(), actor)) {
            throw new AppException(HttpStatus.FORBIDDEN, "Only the author or an admin can delete this comment");
        }
        commentRepository.delete(c);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void decide(Subtitle s, ReviewStatus status, String note, String actor) {
        s.setReviewStatus(status);
        s.setReviewedBy(actor);
        s.setReviewedAt(LocalDateTime.now());
        s.setReviewNote(blankToNull(note));
        subtitleRepository.save(s);
    }

    private Subtitle requireInReview(Long id) {
        Subtitle s = find(id);
        if (s.reviewStatus() != ReviewStatus.IN_REVIEW) {
            throw new AppException(HttpStatus.CONFLICT, "This track isn't waiting for review (it's " + label(s.reviewStatus()) + ")");
        }
        return s;
    }

    private Subtitle find(Long id) {
        return subtitleRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Subtitle track not found with id: " + id));
    }

    private SubtitleComment findComment(Long subtitleId, Long commentId) {
        return commentRepository.findByIdAndSubtitleId(commentId, subtitleId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Comment not found"));
    }

    private String cueAt(Long subtitleId, long atMs) {
        return cueRepository.findBySubtitleIdOrderByPositionAsc(subtitleId).stream()
                .filter(c -> c.getStartMs() <= atMs && atMs < c.getEndMs())
                .map(SubtitleCue::getText).findFirst().orElse(null);
    }

    // "“Khmer” on “Lesson 1”"
    private String describe(Subtitle s) {
        String title = videoRepository.findById(s.getVideoId()).map(Video::getTitle).orElse("video #" + s.getVideoId());
        return "“" + s.getLabel() + "” on “" + title + "”";
    }

    private void publish(Subtitle s, String actor) {
        events.publishEvent(new SubtitleReviewEvent(s.getId(), s.getVideoId(), s.getLabel(), s.getLanguage(), s.reviewStatus(), actor,
                s.getReviewNote()));
    }

    // A notification problem must never undo the review decision itself.
    private void notifyQuietly(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.warn("Couldn't send a review notification", e);
        }
    }

    private static String label(ReviewStatus status) {
        return switch (status) {
            case DRAFT -> "a draft";
            case IN_REVIEW -> "in review";
            case CHANGES_REQUESTED -> "waiting for changes";
            case APPROVED -> "approved";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static CommentResponse toResponse(SubtitleComment c) {
        return new CommentResponse(c.getId(), c.getSubtitleId(), c.getAtMs(), c.getCueText(), c.getBody(), c.getAuthor(), c.isResolved(),
                c.getResolvedBy(), c.getResolvedAt(), c.getCreatedAt());
    }
}
