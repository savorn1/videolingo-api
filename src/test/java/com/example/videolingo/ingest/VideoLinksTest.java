package com.example.videolingo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.videolingo.entity.VideoSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoLinksTest {

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s, YOUTUBE, VIDEO, dQw4w9WgXcQ",
        "youtu.be/dQw4w9WgXcQ, YOUTUBE, VIDEO, dQw4w9WgXcQ",
        "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ, YOUTUBE, VIDEO, dQw4w9WgXcQ",
        "https://youtube.com/shorts/aqz-KE-bpKQ?si=xyz, YOUTUBE, SHORT, aqz-KE-bpKQ",
        "https://www.youtube.com/embed/dQw4w9WgXcQ, YOUTUBE, VIDEO, dQw4w9WgXcQ",
        "https://www.youtube.com/live/dQw4w9WgXcQ, YOUTUBE, LIVE, dQw4w9WgXcQ",
        "https://vimeo.com/1084537, VIMEO, VIDEO, 1084537",
        "https://vimeo.com/channels/staffpicks/76979871, VIMEO, VIDEO, 76979871",
        "https://player.vimeo.com/video/1084537, VIMEO, VIDEO, 1084537",
        "https://www.facebook.com/reel/1234567890123456, FACEBOOK, REEL, 1234567890123456",
        "https://www.facebook.com/watch/?v=1234567890, FACEBOOK, VIDEO, 1234567890",
        "https://m.facebook.com/SomePage/videos/1234567890/, FACEBOOK, VIDEO, 1234567890",
        "https://web.facebook.com/SomePage/videos/my-title/1234567890, FACEBOOK, VIDEO, 1234567890"
    })
    void recognisesPlatforms(String url, VideoSource source, VideoLinks.Kind kind, String id) {
        VideoLinks.Parsed p = VideoLinks.parse(url);
        assertEquals(source, p.source());
        assertEquals(kind, p.kind());
        assertEquals(id, p.externalId());
    }

    @Test
    void canonicalAndEmbedUrls() {
        VideoLinks.Parsed yt = VideoLinks.parse("youtu.be/dQw4w9WgXcQ");
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", yt.canonicalUrl());
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", yt.embedUrl());
        VideoLinks.Parsed unlisted = VideoLinks.parse("https://vimeo.com/123456789/abcdef1234");
        assertEquals("https://vimeo.com/123456789/abcdef1234", unlisted.canonicalUrl());
        assertEquals("https://player.vimeo.com/video/123456789?h=abcdef1234", unlisted.embedUrl());
        VideoLinks.Parsed fb = VideoLinks.parse("https://www.facebook.com/reel/987654321");
        assertEquals(
                "https://www.facebook.com/plugins/video.php?show_text=false&href=https%3A%2F%2Fwww.facebook.com%2Freel%2F987654321",
                fb.embedUrl());
    }

    @Test
    void shortLinksAreRecognisedButResolvedLater() {
        VideoLinks.Parsed share = VideoLinks.parse("https://www.facebook.com/share/r/1AbCdEf/");
        assertEquals(VideoSource.FACEBOOK, share.source());
        assertEquals(VideoLinks.Kind.REEL, share.kind());
        assertNull(share.externalId());
        assertNull(VideoLinks.parse("https://fb.watch/abc123XY/").externalId());
    }

    @Test
    void filesAndPages() {
        assertEquals(
                VideoLinks.Kind.FILE,
                VideoLinks.parse("https://cdn.example.com/media/lesson%201.MP4?sig=1")
                        .kind());
        assertEquals(
                VideoLinks.Kind.PAGE,
                VideoLinks.parse("https://example.com/lessons/42").kind());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "ftp://example.com/a.mp4",
                "javascript:alert(1)",
                "https://user:pw@example.com/a.mp4",
                "not a url",
                "https://localhost/a.mp4",
                "https://www.youtube.com/playlist?list=PL123",
                "https://www.youtube.com/@channel",
                "https://vimeo.com/about",
                "https://www.facebook.com/SomePage"
            })
    void rejectsInvalidOrUnsupported(String url) {
        assertThrows(IllegalArgumentException.class, () -> VideoLinks.parse(url));
    }
}
