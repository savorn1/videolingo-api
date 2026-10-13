package com.example.videolingo.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.videolingo.entity.User;
import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoNote;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.notification.NotificationService;
import com.example.videolingo.repository.UserRepository;
import com.example.videolingo.repository.VideoNoteRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.review.VideoNoteService.NoteRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoNoteServiceTest {

    private final VideoNoteRepository notes = mock(VideoNoteRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final VideoNoteService service = new VideoNoteService(notes, videos, users, notifications);

    @BeforeEach
    void setUp() {
        Video video = Video.builder().id(7L).title("Lesson 4").ownerId(1L).build();
        when(videos.findById(7L)).thenReturn(Optional.of(video));
        when(users.findById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).username("dara").build()));
        when(notes.save(any(VideoNote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aNoteIsKeptTrimmedAndTheOwnerIsTold() {
        var note = service.addNote(7L, new NoteRequest("  The intro is too loud  "), "sok");
        assertEquals("The intro is too loud", note.body());
        assertEquals("sok", note.author());
        verify(notifications).notifyInApp(eq(List.of("dara")), eq("New note on “Lesson 4”"), anyString(), eq("sok"));
    }

    @Test
    void theOwnerIsNotToldAboutTheirOwnNote() {
        service.addNote(7L, new NoteRequest("Fixed the title"), "dara");
        verify(notifications, never()).notifyInApp(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aFailedNotificationKeepsTheNote() {
        org.mockito.Mockito.doThrow(new IllegalStateException("mail down"))
                .when(notifications)
                .notifyInApp(any(), anyString(), anyString(), anyString());
        assertEquals(
                "Check", service.addNote(7L, new NoteRequest("Check"), "sok").body());
    }

    @Test
    void resolvingSaysWhoAndReopeningClearsIt() {
        VideoNote note =
                VideoNote.builder().id(3L).videoId(7L).body("x").author("sok").build();
        when(notes.findByIdAndVideoId(3L, 7L)).thenReturn(Optional.of(note));
        var resolved = service.resolveNote(7L, 3L, true, "dara");
        assertTrue(resolved.resolved());
        assertEquals("dara", resolved.resolvedBy());
        var reopened = service.resolveNote(7L, 3L, false, "dara");
        assertNull(reopened.resolvedBy());
        assertNull(reopened.resolvedAt());
    }

    @Test
    void onlyTheAuthorOrAnAdminCanDelete() {
        VideoNote note =
                VideoNote.builder().id(3L).videoId(7L).body("x").author("sok").build();
        when(notes.findByIdAndVideoId(3L, 7L)).thenReturn(Optional.of(note));
        AppException e = assertThrows(AppException.class, () -> service.deleteNote(7L, 3L, "dara", false));
        assertEquals("Only the author or an admin can delete this note", e.getMessage());
        service.deleteNote(7L, 3L, "sok", false);
        service.deleteNote(7L, 3L, "admin", true);
        verify(notes, org.mockito.Mockito.times(2)).delete(note);
    }

    @Test
    void aMissingVideoOrNoteIsNotFound() {
        when(videos.findById(99L)).thenReturn(Optional.empty());
        assertThrows(AppException.class, () -> service.notes(99L));
        when(notes.findByIdAndVideoId(5L, 7L)).thenReturn(Optional.empty());
        assertThrows(AppException.class, () -> service.resolveNote(7L, 5L, true, "x"));
    }
}
