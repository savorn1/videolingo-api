package com.example.videolingo.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaUrlsTest {

    private static final String BASE = "https://cdn.example.com/media/";

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger signings = new AtomicInteger();

    private MediaUrls urls(boolean enabled) {
        return new MediaUrls(
                "https://cdn.example.com/",
                "media",
                enabled,
                Duration.ofMinutes(180),
                (key, ttl) -> "https://cdn.example.com/media/" + key + "?X-Amz-Expires=" + ttl.toSeconds() + "&n="
                        + signings.incrementAndGet(),
                clock);
    }

    @Test
    void ourFilesAreSignedAndOthersPassThrough() {
        MediaUrls urls = urls(true);
        String signed = urls.forBrowser(BASE + "videos/1/a.mp4");
        assertTrue(signed.startsWith(BASE + "videos/1/a.mp4?X-Amz-Expires=10800"));
        assertEquals("https://youtu.be/x", urls.forBrowser("https://youtu.be/x"));
        assertNull(urls.forBrowser(null));
    }

    @Test
    void aSignedLinkIsReusedForAThirdOfItsLifetime() {
        MediaUrls urls = urls(true);
        String first = urls.forBrowser(BASE + "a.mp4");
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        assertEquals(first, urls.forBrowser(BASE + "a.mp4"));
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertNotEquals(first, urls.forBrowser(BASE + "a.mp4"));
        assertEquals(2, signings.get());
    }

    @Test
    void keysComeFromPlainOrSignedLinksAndAreDecoded() {
        MediaUrls urls = urls(true);
        assertEquals("edits/1/2.mp4", urls.keyOf(BASE + "edits/1/2.mp4?X-Amz-Signature=abc"));
        assertEquals("uploads/my clip+1.mp4", urls.keyOf(BASE + "uploads/my%20clip+1.mp4"));
        assertNull(urls.keyOf("https://other.example.com/media/a.mp4"));
        assertNull(urls.keyOf(BASE));
    }

    @Test
    void linksComingBackAreStoredWithoutTheSignature() {
        MediaUrls urls = urls(true);
        assertEquals(BASE + "thumbs/1.jpg", urls.toStored(urls.forBrowser(BASE + "thumbs/1.jpg")));
        assertEquals("https://img.example.com/x.jpg?size=2", urls.toStored("https://img.example.com/x.jpg?size=2"));
        assertNull(urls.toStored(null));
    }

    @Test
    void switchedOffOrUnconfiguredItChangesNothing() {
        assertEquals(BASE + "a.mp4", urls(false).forBrowser(BASE + "a.mp4"));
        MediaUrls unconfigured = new MediaUrls("", "", true, Duration.ofMinutes(60), (k, t) -> "signed", clock);
        assertEquals(BASE + "a.mp4", unconfigured.forBrowser(BASE + "a.mp4"));
    }

    @Test
    void theRealPresignerMakesAnExpiringLinkToTheSameObject() {
        software.amazon.awssdk.services.s3.presigner.S3Presigner presigner =
                software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                        .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                        .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("key", "secret")))
                        .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build())
                        .endpointOverride(java.net.URI.create("http://localhost:9000"))
                        .build();
        MediaUrls urls = new MediaUrls(presigner, "http://localhost:9000/", "media", true, 120);
        String signed = urls.forBrowser("http://localhost:9000/media/videos/1/a b.mp4");
        assertTrue(signed.startsWith("http://localhost:9000/media/videos/1/a%20b.mp4?"), signed);
        assertTrue(signed.contains("X-Amz-Expires=7200"), signed);
        assertTrue(signed.contains("X-Amz-Signature="), signed);
        assertEquals("videos/1/a b.mp4", urls.keyOf(signed));
        assertEquals("http://localhost:9000/media/videos/1/a%20b.mp4", urls.toStored(signed));
    }
}
