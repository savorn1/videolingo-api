package com.example.videolingo.ingest;

import com.example.videolingo.entity.VideoSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Recognises video links without touching the network: which platform, the
// platform's video id, a canonical watch URL and the embed URL. Pure, so the
// rules are unit-tested (VideoLinksTest).
public final class VideoLinks {

    public enum Kind { VIDEO, SHORT, REEL, LIVE, FILE, PAGE }

    /** A parsed link. {@code externalId} is null for files and unknown pages. */
    public record Parsed(VideoSource source, Kind kind, String externalId, String canonicalUrl, String embedUrl, URI uri) {
    }

    public static final int MAX_LENGTH = 2048;

    static final Set<String> FILE_EXTENSIONS = Set.of("mp4", "m4v", "webm", "mov", "ogv", "ogg", "mkv", "m3u8");

    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YT_PATH = Pattern.compile("^/(shorts|embed|live|v|e)/([A-Za-z0-9_-]{11})(?:[/?#].*)?$");
    private static final Pattern VIMEO_PATH = Pattern.compile("^/(?:(?:channels|groups/[^/]+/videos|album/\\d+/video|showcase/\\d+/video)/[^/]*/?)?(\\d{5,12})(?:/([0-9a-f]{6,20}))?/?$");
    private static final Pattern VIMEO_CHANNEL = Pattern.compile("^/(?:channels|groups)/[^/]+/(?:videos/)?(\\d{5,12})/?$");
    private static final Pattern VIMEO_PLAYER = Pattern.compile("^/video/(\\d{5,12})/?$");
    private static final Pattern FB_REEL = Pattern.compile("^/reel/(\\d{5,25})/?$");
    private static final Pattern FB_VIDEOS = Pattern.compile("^/[^/]+/videos/(?:[^/]+/)?(\\d{5,25})/?$");
    private static final Pattern FB_SHARE = Pattern.compile("^/share/(v|r)/([A-Za-z0-9]+)/?$");

    private VideoLinks() {
    }

    /** Validates and classifies {@code raw}; throws {@link IllegalArgumentException} with a readable reason. */
    public static Parsed parse(String raw) {
        URI uri = normalize(raw);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        if (host.equals("youtu.be")) {
            String id = path.replaceAll("^/|/$", "");
            if (YT_ID.matcher(id).matches()) {
                return youtube(id, Kind.VIDEO, uri);
            }
            throw new IllegalArgumentException("That youtu.be link doesn't contain a video id");
        }
        if (isHost(host, "youtube.com") || isHost(host, "youtube-nocookie.com")) {
            if (path.equals("/watch")) {
                String v = query(uri, "v");
                if (v != null && YT_ID.matcher(v).matches()) {
                    return youtube(v, Kind.VIDEO, uri);
                }
            }
            Matcher m = YT_PATH.matcher(path);
            if (m.matches()) {
                Kind kind = switch (m.group(1)) {
                    case "shorts" -> Kind.SHORT;
                    case "live" -> Kind.LIVE;
                    default -> Kind.VIDEO;
                };
                return youtube(m.group(2), kind, uri);
            }
            throw new IllegalArgumentException("That YouTube link isn't a single video (playlists and channels can't be added)");
        }
        if (isHost(host, "vimeo.com")) {
            Matcher m = host.equals("player.vimeo.com") ? VIMEO_PLAYER.matcher(path) : VIMEO_PATH.matcher(path);
            if (m.matches()) {
                String hash = host.equals("player.vimeo.com") ? query(uri, "h") : (m.groupCount() >= 2 ? m.group(2) : null);
                return vimeo(m.group(1), hash, uri);
            }
            Matcher c = VIMEO_CHANNEL.matcher(path);
            if (c.matches()) {
                return vimeo(c.group(1), null, uri);
            }
            throw new IllegalArgumentException("That Vimeo link isn't a single video");
        }
        if (host.equals("fb.watch")) {
            String code = path.replaceAll("^/|/$", "");
            if (code.matches("[A-Za-z0-9_-]{4,20}")) {
                // Short link: the numeric id is only known after following it (VideoInspector).
                return new Parsed(VideoSource.FACEBOOK, Kind.VIDEO, null, "https://fb.watch/" + code + "/", null, uri);
            }
            throw new IllegalArgumentException("That fb.watch link is incomplete");
        }
        if (isHost(host, "facebook.com")) {
            Matcher reel = FB_REEL.matcher(path);
            if (reel.matches()) {
                return facebook(reel.group(1), Kind.REEL, "https://www.facebook.com/reel/" + reel.group(1), uri);
            }
            if (path.equals("/watch/") || path.equals("/watch") || path.equals("/video.php")) {
                String v = query(uri, "v");
                if (v != null && v.matches("\\d{5,25}")) {
                    return facebook(v, Kind.VIDEO, "https://www.facebook.com/watch/?v=" + v, uri);
                }
            }
            Matcher videos = FB_VIDEOS.matcher(path);
            if (videos.matches()) {
                return facebook(videos.group(1), Kind.VIDEO, "https://www.facebook.com/watch/?v=" + videos.group(1), uri);
            }
            Matcher share = FB_SHARE.matcher(path);
            if (share.matches()) {
                Kind kind = share.group(1).equals("r") ? Kind.REEL : Kind.VIDEO;
                return new Parsed(VideoSource.FACEBOOK, kind, null, "https://www.facebook.com/share/" + share.group(1) + "/" + share.group(2) + "/", null, uri);
            }
            throw new IllegalArgumentException("That Facebook link isn't a video or reel");
        }
        String ext = extension(path);
        if (ext != null && FILE_EXTENSIONS.contains(ext)) {
            return new Parsed(VideoSource.URL, Kind.FILE, null, uri.toString(), null, uri);
        }
        // Anything else: a page that may describe a video (og:video) — decided after fetching.
        return new Parsed(VideoSource.URL, Kind.PAGE, null, uri.toString(), null, uri);
    }

    /** Embed player URL for a stored video, or null when it plays as a file. */
    public static String embedUrl(VideoSource source, String externalId, String videoUrl) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case YOUTUBE -> externalId == null ? null : "https://www.youtube-nocookie.com/embed/" + externalId;
            case VIMEO -> externalId == null ? null : vimeoEmbed(externalId, videoUrl);
            case FACEBOOK -> videoUrl == null ? null
                    : "https://www.facebook.com/plugins/video.php?show_text=false&href=" + java.net.URLEncoder.encode(videoUrl, java.nio.charset.StandardCharsets.UTF_8);
            default -> null;
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────

    static URI normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Paste a link to a video");
        }
        String s = raw.strip();
        if (s.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("That link is too long");
        }
        if (s.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A link can't contain spaces");
        }
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            // "youtu.be/abc" or "www.youtube.com/watch?v=…" pasted without a scheme.
            s = "https://" + s;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a valid link");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http:// and https:// links are supported");
        }
        if (uri.getHost() == null || !uri.getHost().contains(".")) {
            throw new IllegalArgumentException("That link has no valid website address");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Links with a username or password aren't supported");
        }
        return uri;
    }

    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    static String query(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String extension(String path) {
        int slash = path.lastIndexOf('/');
        String last = path.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 ? null : last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Parsed youtube(String id, Kind kind, URI uri) {
        String canonical = kind == Kind.SHORT ? "https://www.youtube.com/shorts/" + id : "https://www.youtube.com/watch?v=" + id;
        return new Parsed(VideoSource.YOUTUBE, kind, id, canonical, "https://www.youtube-nocookie.com/embed/" + id, uri);
    }

    private static Parsed vimeo(String id, String hash, URI uri) {
        // Unlisted videos need their hash to be viewable; keep it in the canonical URL.
        String canonical = "https://vimeo.com/" + id + (hash != null && !hash.isBlank() ? "/" + hash : "");
        return new Parsed(VideoSource.VIMEO, Kind.VIDEO, id, canonical, vimeoEmbed(id, canonical), uri);
    }

    private static String vimeoEmbed(String id, String canonical) {
        String hash = null;
        if (canonical != null) {
            Matcher m = Pattern.compile("vimeo\\.com/\\d+/([0-9a-f]+)").matcher(canonical);
            if (m.find()) {
                hash = m.group(1);
            }
        }
        return "https://player.vimeo.com/video/" + id + (hash != null ? "?h=" + hash : "");
    }

    private static Parsed facebook(String id, Kind kind, String canonical, URI uri) {
        return new Parsed(VideoSource.FACEBOOK, kind, id, canonical, embedUrl(VideoSource.FACEBOOK, id, canonical), uri);
    }
}
