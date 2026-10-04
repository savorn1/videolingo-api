package com.example.videolingo.learn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.entity.Video;
import com.example.videolingo.entity.VideoVisibility;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

// The rule for "can this viewer open this video?", shared by the watch page and everything that lists videos to open
// (Continue watching): a link must never lead to "Video not found".
class WatchableTest {

    private static Video video() {
        return Video.builder().title("t").ownerId(7L).build();
    }

    @Test
    void anEnabledPublicVideoCanBeWatched() {
        assertTrue(LearnService.isWatchable(video(), 1L, false));
    }

    @Test
    void aDisabledVideoCannotEvenIfTheViewerStartedIt() {
        Video v = video();
        v.setEnabled(false);
        assertFalse(LearnService.isWatchable(v, 1L, false));
    }

    @Test
    void aTrashedOrArchivedVideoCannotBeWatched() {
        Video trashed = video();
        trashed.setDeletedAt(LocalDateTime.now());
        assertFalse(LearnService.isWatchable(trashed, 1L, false));
        Video archived = video();
        archived.setArchivedAt(LocalDateTime.now());
        assertFalse(LearnService.isWatchable(archived, 1L, false));
    }

    @Test
    void aPrivateVideoIsOnlyForItsOwnerAndAdmins() {
        Video v = video();
        v.setVisibility(VideoVisibility.PRIVATE);
        assertFalse(LearnService.isWatchable(v, 1L, false));
        assertTrue(LearnService.isWatchable(v, 7L, false));
        assertTrue(LearnService.isWatchable(v, 1L, true));
    }
}
