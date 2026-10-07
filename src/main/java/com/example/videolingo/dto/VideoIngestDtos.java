package com.example.videolingo.dto;

import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.pipeline.AudioToVideoRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

public final class VideoIngestDtos {

    private VideoIngestDtos() {}

    @Data
    public static class InspectRequest {
        @NotBlank
        @Size(max = 2048)
        private String url;
    }

    public record Duplicate(Long id, String title, boolean trashed) {}

    /** What we could find out about a pasted link — every field but url/source may be null. */
    public record InspectResponse(
            String url,
            VideoSource source,
            // VIDEO, SHORT, REEL, LIVE, FILE
            String kind,
            String externalId,
            String embedUrl,
            String title,
            String description,
            Integer durationSeconds,
            String thumbnailUrl,
            Integer width,
            Integer height,
            String author,
            String mimeType,
            Long fileSize,
            LanguageGuess language,
            List<String> warnings,
            List<Duplicate> duplicates) {}

    /**
     * {@code source}: "platform" (reported by the video platform) or "text"
     * (guessed from the title/description). {@code inCatalog}/{@code enabled}
     * say whether it can be picked as is.
     */
    public record LanguageGuess(
            String code, String name, double confidence, String source, boolean inCatalog, boolean enabled) {}

    @Data
    public static class DetectLanguageRequest {
        @Size(max = 200)
        private String title;

        @Size(max = 10_000)
        private String description;
    }

    @Data
    public static class UploadRequest {
        @NotNull
        @Pattern(regexp = "VIDEO|THUMBNAIL|AUDIO|OVERLAY")
        private String kind;

        @NotBlank
        @Size(max = 255)
        private String fileName;

        @Size(max = 100)
        private String contentType;

        @NotNull
        @Min(1)
        private Long size;
    }

    /** A signed, single-use-intent PUT straight to storage. Send exactly these headers and the declared size. */
    public record UploadTicket(
            String key,
            String uploadUrl,
            String method,
            Map<String, String> headers,
            Instant expiresAt,
            String publicUrl,
            String contentType) {}

    /**
     * A large upload started in parts: ask for each part's URL ({@link PartRequest}), PUT the bytes to it, then
     * complete it. {@code publicUrl} is where the file can be read once completed.
     */
    public record MultipartTicket(
            String key, String uploadId, long partSize, int partCount, String publicUrl, String contentType) {}

    @Data
    public static class PartRequest {
        @NotBlank
        private String key;

        @NotBlank
        private String uploadId;

        @NotNull
        @Min(1)
        private Integer partNumber;

        /** The whole file's size, which fixes each part's length. */
        @NotNull
        @Min(1)
        private Long size;
    }

    /** A signed PUT for one part: send exactly {@code length} bytes. */
    public record PartUrl(int partNumber, String url, long length, Instant expiresAt) {}

    @Data
    public static class MultipartRef {
        @NotBlank
        private String key;

        @NotBlank
        private String uploadId;
    }

    @Data
    public static class CreateVideoRequest {
        // "LINK" (url) or "UPLOAD" (storageKey from an upload ticket).
        @NotNull
        @Pattern(regexp = "LINK|UPLOAD")
        private String mode;

        @Size(max = 2048)
        private String url;

        @Size(max = 500)
        private String storageKey;

        @NotBlank
        @Size(max = 200)
        private String title;

        @Size(max = 10_000)
        private String description;

        @Size(max = 10)
        private String language;

        @Min(0)
        @Max(7 * 24 * 3600)
        private Integer durationSeconds;

        @Min(1)
        @Max(16384)
        private Integer width;

        @Min(1)
        @Max(16384)
        private Integer height;

        @Size(max = 1000)
        @Pattern(regexp = "^$|^https?://\\S+$", message = "must be an http(s) address")
        private String thumbnailUrl;

        @Size(max = 200)
        private String sourceAuthor;

        @Size(max = 100)
        private String mimeType;

        private List<Long> categoryIds;
        private boolean enabled = true;
        // Add even though the same video already exists.
        private boolean allowDuplicate;
    }

    @Data
    public static class ReplaceRequest {
        // From an upload ticket, same as CreateVideoRequest.storageKey.
        @NotBlank
        @Size(max = 500)
        private String storageKey;

        @Min(0)
        @Max(7 * 24 * 3600)
        private Integer durationSeconds;

        @Min(1)
        @Max(16384)
        private Integer width;

        @Min(1)
        @Max(16384)
        private Integer height;
    }

    /**
     * A video made from a sound. Both keys come from upload tickets (kind AUDIO
     * for the sound, OVERLAY for the cover picture).
     */
    @Data
    public static class AudioToVideoRequest {
        @NotBlank
        @Size(max = 500)
        private String audioKey;

        @Size(max = 500)
        private String coverKey;
        // A slideshow: pictures with the time each appears (the first at 0). Takes the place of coverKey.
        @Size(max = 30)
        private List<SlideDto> slides;
        // "#rrggbb"; the picture is fitted onto it, or it is the whole picture.
        @Size(max = 7)
        private String background;
        // 360p, 480p, 720p or 1080p.
        @Size(max = 10)
        private String resolution;
        // NONE, WAVES or BARS: a moving waveform along the bottom.
        @Size(max = 10)
        private String waveform;
        // "#rrggbb"; white or black to suit the background when left out.
        @Size(max = 7)
        private String waveColor;
        // Write text on the background when there is no cover picture. The text is
        // cardText, or the video's title when that is left out.
        private boolean titleCard;

        @Size(max = 200)
        private String cardText;

        private boolean normalize;
        private boolean denoise;
        // Transcribe the finished video (needs a spoken language).
        private boolean transcribe;

        @NotBlank
        @Size(max = 200)
        private String title;

        @Size(max = 10_000)
        private String description;

        @Size(max = 10)
        private String language;

        private List<Long> categoryIds;

        // WIDE (default), TALL (9:16) or SQUARE.
        @Size(max = 10)
        private String shape;
        // A slow zoom on the pictures, and dissolves between them.
        private boolean motion;
        private boolean crossfade;
        // A strip of text (e.g. the episode and speaker) over the picture, and a logo (an OVERLAY upload) in a corner.
        @Size(max = 120)
        private String stripText;

        @Size(max = 500)
        private String logoKey;
        // Take the silence off the start and end of the recording.
        private boolean trimSilence;
        // Only this part of the recording (a chapter); null = from the start / to the end.
        @Min(0)
        private Long audioStartMs;

        @Min(0)
        private Long audioEndMs;
        // Burn the transcript into the picture once it is made (needs transcribe).
        private BurnSubtitlesDto burnSubtitles;
        // The waveform's height, % of the picture (10–80); null = 25 % along the bottom, 40 % in the middle.
        private Integer waveHeightPct;
    }

    /** style CLASSIC / BOX / YELLOW, position BOTTOM / TOP. */
    public record BurnSubtitlesDto(String style, String position) {}

    /** Where to cut a long recording into parts of about `partMinutes`. */
    public record ChaptersRequest(
            @NotBlank @Size(max = 500) String audioKey,
            @Min(2) @Max(60) int partMinutes) {}

    public record ChaptersResponse(long totalMs, List<AudioToVideoRules.Range> parts) {}

    /** A short test render of a look with the real sound: just what shows in the picture. */
    @Data
    public static class AudioPreviewRequest {
        @NotBlank
        @Size(max = 500)
        private String audioKey;

        @Size(max = 7)
        private String background;

        @NotBlank
        @Size(max = 10)
        private String waveform;

        @Size(max = 7)
        private String waveColor;

        private boolean normalize;
        private boolean denoise;
        private Integer waveHeightPct;
    }

    public record AudioPreviewResponse(String url, long seconds) {}

    public record SlideDto(
            @NotBlank @Size(max = 500) String key, @Min(0) long startMs) {}

    /** The new (disabled) video and the job that is making its file. */
    public record AudioToVideoResponse(VideoResponse video, ProcessingJobResponse job) {}

    /** Several stored videos joined into one new (hidden) video, in the order given. */
    @Data
    public static class MergeVideosRequest {
        @NotNull
        @Size(min = 2, max = 10)
        private List<Long> videoIds;

        @NotBlank
        @Size(max = 200)
        private String title;

        @Size(max = 10_000)
        private String description;
        // 360p, 480p, 720p or 1080p.
        @Size(max = 10)
        private String resolution;
        // NONE or FADE.
        @Size(max = 10)
        private String transition;

        @Size(max = 10)
        private String language;

        private List<Long> categoryIds;
    }

    public record MergeVideosResponse(VideoResponse video, ProcessingJobResponse job) {}
}
