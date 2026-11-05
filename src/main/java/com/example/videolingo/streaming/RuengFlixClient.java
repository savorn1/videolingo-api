package com.example.videolingo.streaming;

import com.example.videolingo.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

// Calls out of the admin portal to the RuengFlix side: movie-api's health check, and RuengFlix's own
// server for sending a new-episode alert and dropping its catalog cache (both behind RuengFlix's
// NUXT_NOTIFY_SECRET). Also checks whether the bucket answers anonymous reads, which would make
// signed links pointless. Every call has a short timeout and never follows redirects.
@Component
public class RuengFlixClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** One service's state for the status panel. */
    public record Check(String name, boolean configured, boolean ok, String detail, Long millis) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper jackson;
    private final S3Client s3;
    private final String movieApiUrl;
    private final String movieApiToken;
    private final String siteUrl;
    private final String siteSecret;
    private final String bucket;
    private final String publicEndpoint;

    public RuengFlixClient(
            ObjectMapper jackson,
            S3Client s3,
            @Value("${streaming.movie-api-url:}") String movieApiUrl,
            @Value("${streaming.movie-api-token:}") String movieApiToken,
            @Value("${streaming.site-url:}") String siteUrl,
            @Value("${streaming.site-secret:}") String siteSecret,
            @Value("${s3.bucket:}") String bucket,
            @Value("${s3.public-endpoint:}") String publicEndpoint) {
        this.jackson = jackson;
        this.s3 = s3;
        this.movieApiUrl = trimSlash(movieApiUrl);
        this.movieApiToken = movieApiToken == null ? "" : movieApiToken;
        this.siteUrl = trimSlash(siteUrl);
        this.siteSecret = siteSecret == null ? "" : siteSecret;
        this.bucket = bucket == null ? "" : bucket;
        this.publicEndpoint = trimSlash(publicEndpoint);
    }

    /** RuengFlix's public address, for "Open on RuengFlix" links; "" when not set. */
    public String siteUrl() {
        return siteUrl;
    }

    public boolean canControlSite() {
        return !siteUrl.isEmpty() && !siteSecret.isEmpty();
    }

    public Check movieApi() {
        if (movieApiUrl.isEmpty()) {
            return new Check("movie-api", false, false, "Set MOVIE_API_URL", null);
        }
        return probe("movie-api", movieApiUrl + "/health");
    }

    public Check site() {
        if (siteUrl.isEmpty()) {
            return new Check("RuengFlix", false, false, "Set RUENGFLIX_URL", null);
        }
        return probe("RuengFlix", siteUrl + "/");
    }

    /**
     * Whether the bucket hands out a stored file without a signature. Public = the signed, expiring links
     * protect nothing. Checked on one object, the way a browser would ask.
     */
    public Check bucketPrivacy() {
        if (bucket.isEmpty() || publicEndpoint.isEmpty()) {
            return new Check("Bucket privacy", false, false, "Set S3_BUCKET and S3_PUBLIC_ENDPOINT", null);
        }
        long start = System.nanoTime();
        try {
            S3Object first =
                    s3
                            .listObjectsV2(ListObjectsV2Request.builder()
                                    .bucket(bucket)
                                    .maxKeys(1)
                                    .build())
                            .contents()
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (first == null) {
                return new Check("Bucket privacy", true, true, "The bucket is empty", ms(start));
            }
            String encoded = Arrays.stream(first.key().split("/"))
                    .map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20"))
                    .collect(Collectors.joining("/"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(publicEndpoint + "/" + bucket + "/" + encoded))
                    .timeout(TIMEOUT)
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean open = status >= 200 && status < 300;
            return new Check(
                    "Bucket privacy",
                    true,
                    !open,
                    open
                            ? "Anyone can download files without a signed link. With MinIO: mc anonymous set none <alias>/"
                                    + bucket
                            : "Private: files need a signed link (HTTP " + status + " without one)",
                    ms(start));
        } catch (Exception e) {
            return new Check("Bucket privacy", true, false, "Could not check: " + message(e), ms(start));
        }
    }

    /** Has RuengFlix message every follower of a series about an episode; returns its counts. */
    public Map<String, Object> sendAlert(long seriesId, int episode) {
        return postToSite("/api/notify", Map.of("seriesId", seriesId, "ep", episode, "source", "MANUAL"));
    }

    /** Makes RuengFlix drop its cached catalog, so changes show at once instead of within a minute. */
    /**
     * Asks movie-api to act on a RuengFlix order (mark it paid, or cancel it): movie-api delivers what was
     * bought, so the coins, unlocks and VIP time follow the same rules however the payment was confirmed.
     */
    public Map<String, Object> orderAction(long orderId, String action, String actor) {
        return movieApiPost("/orders/" + orderId + "/" + action, Map.of("by", actor), "Order not found");
    }

    /** Refund a paid order on movie-api, which takes back what it delivered. */
    public Map<String, Object> refundOrder(long orderId, String actor, String reason) {
        return movieApiPost("/orders/" + orderId + "/refund", Map.of("by", actor, "reason", reason), "Order not found");
    }

    /** Give (or take back) coins through movie-api, which keeps the wallet and its ledger. */
    public Map<String, Object> grantCoins(long viewerId, int coins, String actor, String reason) {
        return movieApiPost(
                "/viewers/" + viewerId + "/coins/grant",
                Map.of("coins", coins, "by", actor, "reason", reason),
                "Viewer not found");
    }

    /**
     * POST JSON to movie-api with MOVIE_API_TOKEN. movie-api's own 4xx messages (for example "Already
     * refunded") come back to the admin as they are.
     */
    private Map<String, Object> movieApiPost(String path, Map<String, Object> body, String notFound) {
        if (movieApiUrl.isEmpty() || movieApiToken.isEmpty()) {
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "movie-api isn't connected: set MOVIE_API_URL and MOVIE_API_TOKEN on the server");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(movieApiUrl + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + movieApiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jackson.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new AppException(
                        HttpStatus.BAD_GATEWAY, "movie-api refused the token: MOVIE_API_TOKEN must match movie-api's");
            }
            if (response.statusCode() == 404) {
                throw new AppException(HttpStatus.NOT_FOUND, notFound);
            }
            if (response.statusCode() == 400 || response.statusCode() == 409) {
                throw new AppException(HttpStatus.valueOf(response.statusCode()), messageOf(response.body()));
            }
            if (response.statusCode() >= 300) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "movie-api answered HTTP " + response.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jackson.readValue(response.body(), LinkedHashMap.class);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Interrupted");
        } catch (java.io.IOException e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "movie-api is unreachable: " + e.getMessage());
        }
    }

    /** movie-api's error message ({"message": "..."} or a list of them), or a generic one */
    private String messageOf(String body) {
        try {
            Object message = jackson.readValue(body, LinkedHashMap.class).get("message");
            if (message instanceof java.util.List<?> list && !list.isEmpty()) return String.valueOf(list.get(0));
            if (message != null) return String.valueOf(message);
        } catch (java.io.IOException ignored) {
            // Not JSON: fall through
        }
        return "movie-api refused the request";
    }

    public Map<String, Object> refreshSite() {
        return postToSite("/api/refresh", Map.of());
    }

    private Map<String, Object> postToSite(String path, Map<String, Object> body) {
        if (!canControlSite()) {
            throw new AppException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RuengFlix isn't connected: set RUENGFLIX_URL and RUENGFLIX_NOTIFY_SECRET on the server");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(siteUrl + path))
                    // Sending to many followers takes a while (RuengFlix paces itself under Telegram's limit)
                    .timeout(path.equals("/api/notify") ? Duration.ofMinutes(2) : TIMEOUT)
                    .header("Authorization", "Bearer " + siteSecret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jackson.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new AppException(
                        HttpStatus.BAD_GATEWAY,
                        "RuengFlix refused the secret: RUENGFLIX_NOTIFY_SECRET must equal its NUXT_NOTIFY_SECRET");
            }
            if (response.statusCode() >= 400) {
                throw new AppException(
                        HttpStatus.BAD_GATEWAY,
                        "RuengFlix answered HTTP " + response.statusCode() + ": " + shorten(response.body()));
            }
            String text = response.body();
            if (text == null || text.isBlank()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jackson.readValue(text, LinkedHashMap.class);
            return parsed;
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Interrupted while calling RuengFlix");
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Could not reach RuengFlix: " + message(e));
        }
    }

    private Check probe(String name, String url) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean ok = status >= 200 && status < 400;
            return new Check(name, true, ok, ok ? "Up (HTTP " + status + ")" : "HTTP " + status, ms(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(name, true, false, "Interrupted", ms(start));
        } catch (Exception e) {
            return new Check(name, true, false, "Unreachable: " + message(e), ms(start));
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.strip().replaceAll("/+$", "");
    }
}
