package com.example.videolingo.storage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Links to our own stored files (videos, thumbnails, edit results, voice-overs, uploads) as the browser
 * gets them. The database keeps the plain bucket address (publicEndpoint/bucket/key); every response
 * hands out a signed link that stops working after `media.url-ttl-minutes`, so a link copied out of the
 * browser's developer tools is useless soon after, and the bucket itself can be private.
 *
 * <p>A signed link is reused for a third of its lifetime: the same file keeps the same address across
 * responses for a while (the browser can cache it, the player isn't reloaded), and any link handed out is
 * still good for at least two thirds of the lifetime. Links to anything else (YouTube, a pasted URL) are
 * passed through unchanged. Turned off with `media.signed-urls=false` (then the bucket must be public).
 */
@Component
public class MediaUrls {

    private static final int CACHE_SIZE = 20_000;

    private final String prefix;
    private final boolean enabled;
    private final Duration ttl;
    private final BiFunction<String, Duration, String> presign;
    private final Clock clock;
    private final Map<String, Signed> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Signed> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private record Signed(String url, Instant reuseUntil) {}

    @Autowired
    public MediaUrls(
            S3Presigner presigner,
            @Value("${s3.public-endpoint:}") String publicEndpoint,
            @Value("${s3.bucket:}") String bucket,
            @Value("${media.signed-urls:true}") boolean enabled,
            @Value("${media.url-ttl-minutes:180}") long ttlMinutes) {
        this(
                publicEndpoint,
                bucket,
                enabled,
                Duration.ofMinutes(Math.max(5, ttlMinutes)),
                (key, duration) -> presigner
                        .presignGetObject(r -> r.signatureDuration(duration)
                                .getObjectRequest(g -> g.bucket(bucket).key(key)))
                        .url()
                        .toString(),
                Clock.systemUTC());
    }

    /** For tests: `presign` turns a key and a lifetime into a signed link. */
    public MediaUrls(
            String publicEndpoint,
            String bucket,
            boolean enabled,
            Duration ttl,
            BiFunction<String, Duration, String> presign,
            Clock clock) {
        boolean configured = publicEndpoint != null && !publicEndpoint.isBlank() && bucket != null && !bucket.isBlank();
        this.prefix = configured ? publicEndpoint.replaceAll("/+$", "") + "/" + bucket + "/" : null;
        this.enabled = enabled && configured;
        this.ttl = ttl;
        this.presign = presign;
        this.clock = clock;
    }

    /** The object key when `url` (signed or not) is one of our stored files; null otherwise. */
    public String keyOf(String url) {
        if (prefix == null || url == null) {
            return null;
        }
        String bare = withoutQuery(url);
        if (!bare.startsWith(prefix) || bare.length() == prefix.length()) {
            return null;
        }
        // Keys are stored as written; decode %XX only (a literal '+' stays a '+').
        return URLDecoder.decode(bare.substring(prefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** What the browser gets: a signed link for one of our files, anything else unchanged (null stays null). */
    public String forBrowser(String url) {
        String key = keyOf(url);
        if (!enabled || key == null) {
            return url;
        }
        Instant now = clock.instant();
        synchronized (cache) {
            Signed hit = cache.get(key);
            if (hit != null && now.isBefore(hit.reuseUntil())) {
                return hit.url();
            }
            String signed = presign.apply(key, ttl);
            cache.put(key, new Signed(signed, now.plus(ttl.dividedBy(3))));
            return signed;
        }
    }

    /** What to save when a link comes back from the browser: our file's plain address (no signature); anything else unchanged. */
    public String toStored(String url) {
        return keyOf(url) != null ? withoutQuery(url) : url;
    }

    /** A link a server-side tool (ffmpeg) can read: signed for our files, as-is for anything else. */
    public String forServer(String url) {
        return forBrowser(url);
    }

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
