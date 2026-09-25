package com.example.videolingo.ingest;

import com.example.videolingo.entity.VideoSource;
import com.example.videolingo.ingest.VideoLinks.Kind;
import com.example.videolingo.ingest.VideoLinks.Parsed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Fetches what each platform will tell us about a video. Every lookup is
// best-effort: a missing field becomes a warning, never a failure, unless the
// video clearly doesn't exist or isn't playable. All requests go through
// SafeHttp (no private-network access).
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoInspector {

    /** Raw platform facts, before language mapping and duplicate checks. */
    public record Facts(Parsed link, String url, String title, String description, Integer durationSeconds, String thumbnailUrl,
                        Integer width, Integer height, String author, String mimeType, Long fileSize,
                        // Spoken language the platform itself reports (YouTube's auto-caption track), if any.
                        String platformLanguage, List<String> warnings) {
    }

    /** The link can't be used — message is shown as is. */
    public static class InspectException extends RuntimeException {
        public InspectException(String message) {
            super(message);
        }
    }

    private static final int PAGE_BYTES = 3 * 1024 * 1024;
    private static final int JSON_BYTES = 256 * 1024;
    private static final Map<String, String> BROWSER = Map.of("User-Agent", SafeHttp.BROWSER_UA, "Accept-Language", "en;q=0.9");
    // Facebook shows crawlers the public Open Graph tags instead of a login wall.
    private static final Map<String, String> FB_CRAWLER = Map.of("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "Accept-Language", "en-US,en;q=0.9");

    private final SafeHttp http;
    private final ObjectMapper json;

    public Facts inspect(Parsed link) {
        try {
            return switch (link.source()) {
                case YOUTUBE -> youtube(link);
                case VIMEO -> vimeo(link);
                case FACEBOOK -> facebook(link);
                default -> link.kind() == Kind.FILE ? file(link, link.uri()) : page(link);
            };
        } catch (SafeHttp.BlockedException e) {
            throw new InspectException(e.getMessage());
        } catch (IOException e) {
            log.info("Could not reach {}: {}", link.uri().getHost(), e.toString());
            throw new InspectException("Couldn't reach " + link.uri().getHost() + " — check the link, or try again in a moment");
        }
    }

    // ── YouTube: oEmbed (title, channel, thumbnail) + watch page (length, description, caption language)

    private Facts youtube(Parsed link) throws IOException {
        List<String> warnings = new ArrayList<>();
        SafeHttp.Response oembed = http.get(URI.create("https://www.youtube.com/oembed?format=json&url=" + enc(link.canonicalUrl())), BROWSER, JSON_BYTES);
        if (oembed.status() == 404 || oembed.status() == 400) {
            throw new InspectException("This YouTube video doesn't exist, or it's private");
        }
        if (oembed.status() == 401 || oembed.status() == 403) {
            throw new InspectException("This YouTube video can't be embedded — its owner has turned embedding off, or it's private");
        }
        JsonNode o = oembed.ok() ? json.readTree(oembed.body()) : json.createObjectNode();
        String title = text(o, "title");
        String author = text(o, "author_name");
        String thumb = text(o, "thumbnail_url");

        Integer duration = null;
        String description = null;
        String captionLanguage = null;
        SafeHttp.Response page = http.get(URI.create("https://www.youtube.com/watch?v=" + link.externalId() + "&hl=en"),
                Map.of("User-Agent", SafeHttp.BROWSER_UA, "Accept-Language", "en;q=0.9", "Cookie", "CONSENT=YES+1"), PAGE_BYTES);
        if (page.ok()) {
            String html = page.text();
            duration = intOrNull(match(html, "\"lengthSeconds\":\"(\\d+)\""));
            if (duration == null) {
                duration = Html.isoDuration(Html.first(Html.meta(html), "duration"));
            }
            description = jsonString(match(html, "\"shortDescription\":(\"(?:[^\"\\\\]|\\\\.)*\")"));
            captionLanguage = asrLanguage(html);
            if (title == null) {
                title = jsonString(match(html, "\"title\":(\"(?:[^\"\\\\]|\\\\.)*\"),\"lengthSeconds\""));
            }
        } else if (page.finalUri().getHost() != null && page.finalUri().getHost().endsWith("google.com")) {
            // Google's bot check ("sorry" page) — happens after many lookups from one server.
            warnings.add("YouTube is limiting automatic lookups from this server right now, so the description couldn't be read — the length is taken from the player preview");
        } else {
            warnings.add("YouTube didn't return the video page (HTTP " + page.status() + "), so the length and description couldn't be read");
        }
        // Prefer the full-resolution thumbnail when the video has one.
        String maxres = "https://i.ytimg.com/vi/" + link.externalId() + "/maxresdefault.jpg";
        if (http.probe(URI.create(maxres), BROWSER).ok()) {
            thumb = maxres;
        }
        if (duration == null && page.ok()) {
            warnings.add(link.kind() == Kind.LIVE ? "This is a live stream, so it has no fixed length" : "The length couldn't be read");
        }
        boolean vertical = link.kind() == Kind.SHORT;
        return new Facts(link, link.canonicalUrl(), title, description, duration, thumb, vertical ? 1080 : null, vertical ? 1920 : null,
                author, null, null, captionLanguage, warnings);
    }

    // The auto-generated ("asr") caption track is in the language actually spoken.
    static String asrLanguage(String html) {
        String tracks = match(html, "\"captionTracks\":\\[(.*?)\\]");
        if (tracks == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\{[^{}]*?\"languageCode\":\"([a-zA-Z-]+)\"[^{}]*?\"kind\":\"asr\"[^{}]*?\\}").matcher(tracks);
        if (m.find()) {
            return m.group(1);
        }
        Matcher alt = Pattern.compile("\"kind\":\"asr\"[^{}]*?\"languageCode\":\"([a-zA-Z-]+)\"").matcher(tracks);
        return alt.find() ? alt.group(1) : null;
    }

    // ── Vimeo: oEmbed has everything we need

    private Facts vimeo(Parsed link) throws IOException {
        SafeHttp.Response res = http.get(URI.create("https://vimeo.com/api/oembed.json?width=1280&url=" + enc(link.canonicalUrl())), BROWSER, JSON_BYTES);
        if (res.status() == 404) {
            throw new InspectException("This Vimeo video doesn't exist, or it's private");
        }
        if (res.status() == 403) {
            throw new InspectException("This Vimeo video can't be embedded — its owner restricts where it can be shown");
        }
        if (!res.ok()) {
            throw new InspectException("Vimeo didn't return details for this video (HTTP " + res.status() + ")");
        }
        JsonNode o = json.readTree(res.body());
        List<String> warnings = new ArrayList<>();
        return new Facts(link, link.canonicalUrl(), text(o, "title"), blankToNull(text(o, "description")), intNode(o, "duration"),
                text(o, "thumbnail_url"), intNode(o, "width"), intNode(o, "height"), text(o, "author_name"), null, null, null, warnings);
    }

    // ── Facebook: public Open Graph tags (no API token needed)

    private Facts facebook(Parsed link) throws IOException {
        List<String> warnings = new ArrayList<>();
        SafeHttp.Response res = http.get(link.uri(), FB_CRAWLER, PAGE_BYTES);
        Parsed resolved = link;
        // Short links (fb.watch, /share/…) end up on the real video URL.
        if (link.externalId() == null) {
            try {
                Parsed p = VideoLinks.parse(res.finalUri().toString());
                if (p.source() == VideoSource.FACEBOOK && p.externalId() != null) {
                    resolved = p;
                }
            } catch (IllegalArgumentException ignored) {
                // stays unresolved
            }
        }
        Map<String, List<String>> meta = res.ok() ? Html.meta(res.text()) : Map.of();
        if (resolved.externalId() == null) {
            String ogUrl = Html.first(meta, "og:url");
            if (ogUrl != null) {
                try {
                    Parsed p = VideoLinks.parse(ogUrl);
                    if (p.source() == VideoSource.FACEBOOK && p.externalId() != null) {
                        resolved = p;
                    }
                } catch (IllegalArgumentException ignored) {
                    // stays unresolved
                }
            }
        }
        if (resolved.externalId() == null) {
            throw new InspectException("Couldn't find the video behind that Facebook link — open it in a browser and copy the full video or reel address");
        }
        String[] titleAndAuthor = cleanFacebookTitle(Html.first(meta, "og:title", "twitter:title"));
        String title = titleAndAuthor[0];
        String author = titleAndAuthor[1];
        if (title != null && title.toLowerCase(Locale.ROOT).matches("^(log in|facebook|log into facebook).*")) {
            title = null; // a login wall, not the video
        }
        String description = Html.first(meta, "og:description", "description");
        String thumb = Html.first(meta, "og:image", "twitter:image");
        Integer width = intOrNull(Html.first(meta, "og:video:width"));
        Integer height = intOrNull(Html.first(meta, "og:video:height"));
        Integer duration = intOrNull(Html.first(meta, "video:duration", "og:video:duration"));
        if (title == null && description == null && thumb == null) {
            warnings.add("Facebook didn't share this video's details (it may be private or limited to logged-in viewers) — fill them in below");
        } else if (duration == null) {
            warnings.add("Facebook doesn't publish the length of this video — it's filled in once the video is watched, or you can enter it");
        }
        if (resolved.kind() == Kind.REEL && width == null) {
            width = 1080;
            height = 1920;
        }
        return new Facts(resolved, resolved.canonicalUrl(), title, description, duration, thumb, width, height, author, null, null, null, warnings);
    }

    // ── A video file hosted elsewhere

    private Facts file(Parsed link, URI uri) throws IOException {
        SafeHttp.Response res = http.probe(uri, BROWSER);
        if (res.status() == 404 || res.status() == 410) {
            throw new InspectException("There's no file at that address (HTTP " + res.status() + ")");
        }
        if (!res.ok()) {
            throw new InspectException("The file couldn't be reached (HTTP " + res.status() + ")");
        }
        String type = res.contentType() == null ? null : res.contentType().split(";")[0].strip().toLowerCase(Locale.ROOT);
        String ext = VideoLinks.extension(res.finalUri().getRawPath() == null ? "" : res.finalUri().getRawPath());
        boolean hls = "m3u8".equals(ext) || (type != null && type.contains("mpegurl"));
        if (type != null && !type.startsWith("video/") && !hls && !type.equals("application/octet-stream") && !type.equals("binary/octet-stream")) {
            throw new InspectException("That address isn't a video file (it's " + type + ")");
        }
        List<String> warnings = new ArrayList<>();
        if (hls) {
            warnings.add("This is an HLS stream (.m3u8) — it plays in Safari, but most other browsers need a player that supports HLS");
        } else if ("mkv".equals(ext) || "video/x-matroska".equals(type)) {
            warnings.add("MKV files don't play in every browser — MP4 or WebM is safer");
        }
        warnings.add("The length, size and thumbnail are read by your browser from the file itself");
        String name = java.net.URLDecoder.decode(lastSegment(res.finalUri()), StandardCharsets.UTF_8);
        Parsed finalLink = new Parsed(VideoSource.URL, Kind.FILE, null, res.finalUri().toString(), null, res.finalUri());
        return new Facts(finalLink, res.finalUri().toString(), titleFromFileName(name), null, null, null, null, null, null,
                hls ? "application/vnd.apple.mpegurl" : type != null && type.startsWith("video/") ? type : null,
                res.contentLength() > 0 ? res.contentLength() : null, null, warnings);
    }

    // ── Any other page: use the video it declares (og:video), if there is one

    private Facts page(Parsed link) throws IOException {
        SafeHttp.Response res = http.get(link.uri(), BROWSER, PAGE_BYTES);
        if (!res.ok()) {
            throw new InspectException("That page couldn't be opened (HTTP " + res.status() + ")");
        }
        String type = res.contentType() == null ? "" : res.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("video/")) {
            return file(link, res.finalUri());
        }
        if (!type.contains("html")) {
            throw new InspectException("That link isn't a video or a page with a video");
        }
        String html = res.text();
        Map<String, List<String>> meta = Html.meta(html);
        String declared = Html.first(meta, "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream");
        if (declared == null) {
            throw new InspectException("No video found on that page. Supported: YouTube, Vimeo and Facebook links, or a direct link to a video file (.mp4, .webm, .mov…)");
        }
        URI videoUri = res.finalUri().resolve(declared.replace(" ", "%20"));
        Parsed inner;
        try {
            inner = VideoLinks.parse(videoUri.toString());
        } catch (IllegalArgumentException e) {
            throw new InspectException("The video on that page can't be used: " + e.getMessage());
        }
        Facts base = switch (inner.source()) {
            case YOUTUBE -> youtube(inner);
            case VIMEO -> vimeo(inner);
            case FACEBOOK -> facebook(inner);
            default -> file(inner, inner.uri());
        };
        // The page's own title/description beat a file name.
        String title = Html.first(meta, "og:title", "twitter:title");
        return new Facts(base.link(), base.url(), title != null ? title : base.title() != null ? base.title() : Html.title(html),
                base.description() != null ? base.description() : Html.first(meta, "og:description", "description"),
                base.durationSeconds() != null ? base.durationSeconds() : intOrNull(Html.first(meta, "og:video:duration", "video:duration")),
                base.thumbnailUrl() != null ? base.thumbnailUrl() : Html.first(meta, "og:image"),
                base.width() != null ? base.width() : intOrNull(Html.first(meta, "og:video:width")),
                base.height() != null ? base.height() : intOrNull(Html.first(meta, "og:video:height")),
                base.author(), base.mimeType() != null ? base.mimeType() : Html.first(meta, "og:video:type"), base.fileSize(), base.platformLanguage(),
                base.warnings());
    }

    // Facebook video titles look like "1.2M views · 3K reactions | Real title | By Page".
    static String[] cleanFacebookTitle(String raw) {
        if (raw == null) {
            return new String[]{null, null};
        }
        List<String> parts = new ArrayList<>(List.of(raw.split("\\s+\\|\\s+")));
        if (parts.size() > 1 && parts.get(0).contains("·")) {
            parts.remove(0);
        }
        String author = null;
        if (parts.size() > 1) {
            Matcher by = Pattern.compile("^(?:By|De|Por|Von|Par|Di)\\s+(.+)$").matcher(parts.get(parts.size() - 1));
            if (by.matches()) {
                author = by.group(1).strip();
                parts.remove(parts.size() - 1);
            }
        }
        if (parts.size() > 1 && parts.get(parts.size() - 1).equalsIgnoreCase("Facebook")) {
            parts.remove(parts.size() - 1);
        }
        String title = String.join(" | ", parts).strip();
        return new String[]{title.isEmpty() ? null : title, author};
    }

    // ── helpers

    static String titleFromFileName(String name) {
        String base = name.replaceAll("\\.[A-Za-z0-9]{2,5}$", "").replaceAll("[_\\-.]+", " ").replaceAll("\\s+", " ").strip();
        if (base.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private static String lastSegment(URI uri) {
        String p = uri.getRawPath() == null ? "" : uri.getRawPath();
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private String jsonString(String quoted) {
        if (quoted == null) {
            return null;
        }
        try {
            return blankToNull(json.readValue(quoted, String.class));
        } catch (IOException e) {
            return null;
        }
    }

    private static String match(String s, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : blankToNull(v.asText());
    }

    private static Integer intNode(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.canConvertToInt() || v.asInt() <= 0 ? null : v.asInt();
    }

    private static Integer intOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = (int) Math.round(Double.parseDouble(s.strip()));
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
