package com.example.videolingo.pipeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// Text and image layers drawn over a video (titles, captions, logos,
// watermarks). Every layer becomes a PNG — text is drawn by TextRenderer, so
// ffmpeg doesn't need drawtext/freetype — and is composited with `overlay`,
// with its own position, opacity, timing and entrance. Pure: validation,
// summary and filter graph only, unit-tested without ffmpeg.
public final class OverlayRules {

    public static final int MAX_LAYERS = 20;
    public static final int MAX_TEXT = 500;
    public static final double MIN_SIZE_PCT = 1;
    public static final double MAX_SIZE_PCT = 30;
    /** Uploaded images live under this prefix; nothing else in the bucket may be named in a request. */
    public static final String UPLOAD_PREFIX = "overlay-uploads/";

    public static final Set<String> KINDS = Set.of("TEXT", "IMAGE");
    public static final Set<String> ALIGNS = Set.of("LEFT", "CENTER", "RIGHT");
    public static final Set<String> ANIMATIONS = Set.of("NONE", "FADE", "SLIDE_UP", "SLIDE_LEFT");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * One layer. x/y: where its centre sits, as fractions of the frame (0–1).
     * TEXT: sizePct is the font size as % of the video's height; weight is
     * CSS-style (100–900); background null = none. IMAGE: widthPct is its
     * width as % of the video's. endMs null = to the end.
     */
    public record Layer(
            String kind,
            String text,
            String font,
            int weight,
            double sizePct,
            String color,
            String background,
            double backgroundOpacity,
            String align,
            String imageKey,
            double widthPct,
            double x,
            double y,
            double opacity,
            long startMs,
            Long endMs,
            String animation) {
        // Not isText(): Jackson would take that for the `text` property and drop the real one.
        public boolean textual() {
            return "TEXT".equals(kind);
        }
    }

    public record Spec(List<Layer> layers) {
        public Spec {
            layers = layers == null ? List.of() : List.copyOf(layers);
        }
    }

    public record Graph(String filter) {}

    private OverlayRules() {}

    // ── validation ───────────────────────────────────────────────────────

    /** Null = valid; a message otherwise. durationMs null = unknown. */
    public static String validate(Spec s, Long durationMs) {
        if (s.layers().isEmpty()) {
            return "Add a text or image layer first";
        }
        if (s.layers().size() > MAX_LAYERS) {
            return "At most " + MAX_LAYERS + " layers";
        }
        for (int i = 0; i < s.layers().size(); i++) {
            String err = validateLayer(s.layers().get(i), durationMs);
            if (err != null) {
                return "Layer " + (i + 1) + ": " + err;
            }
        }
        return null;
    }

    private static String validateLayer(Layer l, Long durationMs) {
        if (l.kind() == null || !KINDS.contains(l.kind())) {
            return "unknown kind";
        }
        if (l.textual()) {
            if (l.text() == null || l.text().isBlank()) {
                return "the text is empty";
            }
            if (l.text().length() > MAX_TEXT) {
                return "text can be at most " + MAX_TEXT + " characters";
            }
            if (l.font() == null || l.font().isBlank() || l.font().length() > 100) {
                return "pick a font";
            }
            if (l.weight() < 100 || l.weight() > 900) {
                return "font weight must be between 100 and 900";
            }
            if (l.sizePct() < MIN_SIZE_PCT || l.sizePct() > MAX_SIZE_PCT) {
                return "font size must be between " + (int) MIN_SIZE_PCT + "% and " + (int) MAX_SIZE_PCT
                        + "% of the video's height";
            }
            if (l.color() == null || !HEX.matcher(l.color()).matches()) {
                return "the text colour must look like #RRGGBB";
            }
            if (l.background() != null && !HEX.matcher(l.background()).matches()) {
                return "the background colour must look like #RRGGBB";
            }
            if (l.backgroundOpacity() < 0 || l.backgroundOpacity() > 1) {
                return "background opacity must be between 0 and 1";
            }
            if (l.align() == null || !ALIGNS.contains(l.align())) {
                return "alignment must be LEFT, CENTER or RIGHT";
            }
        } else {
            if (!isUploadKey(l.imageKey())) {
                return "the image must be a file uploaded here";
            }
            if (l.widthPct() < 1 || l.widthPct() > 100) {
                return "width must be between 1% and 100% of the video's";
            }
        }
        if (l.x() < 0 || l.x() > 1 || l.y() < 0 || l.y() > 1) {
            return "the position must be inside the frame";
        }
        if (l.opacity() < 0.05 || l.opacity() > 1) {
            return "opacity must be between 5% and 100%";
        }
        if (l.startMs() < 0 || (l.endMs() != null && l.endMs() <= l.startMs())) {
            return "it must end after it starts";
        }
        if (durationMs != null && l.startMs() >= durationMs) {
            return "it starts after the end of the video";
        }
        if (l.animation() == null || !ANIMATIONS.contains(l.animation())) {
            return "unknown animation";
        }
        return null;
    }

    public static boolean isUploadKey(String key) {
        return key != null
                && key.startsWith(UPLOAD_PREFIX)
                && !key.contains("..")
                && key.length() > UPLOAD_PREFIX.length();
    }

    /** "2 text layers, 1 image" — for the review list. */
    public static String describe(Spec s) {
        long texts = s.layers().stream().filter(Layer::textual).count();
        long images = s.layers().size() - texts;
        List<String> parts = new ArrayList<>();
        if (texts > 0) {
            String first = s.layers().stream()
                    .filter(Layer::textual)
                    .findFirst()
                    .map(Layer::text)
                    .orElse("")
                    .strip()
                    .replaceAll("\\s+", " ");
            parts.add(
                    texts == 1
                            ? "Text “" + (first.length() > 40 ? first.substring(0, 39) + "…" : first) + "”"
                            : texts + " text layers");
        }
        if (images > 0) {
            parts.add(images == 1 ? "1 image" : images + " images");
        }
        return String.join(", ", parts);
    }

    // ── graph ────────────────────────────────────────────────────────────

    /**
     * Input 0 is the video; input i+1 is layer i's PNG (looped). Layers are
     * drawn in order, so later ones cover earlier ones. Produces [vout].
     */
    public static Graph build(Spec s, int videoWidth, long durationMs) {
        List<String> graph = new ArrayList<>();
        String current = "[0:v]";
        List<Layer> layers = s.layers();
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            long end = l.endMs() == null ? durationMs : Math.min(l.endMs(), durationMs);
            double st = l.startMs() / 1000.0;
            double et = end / 1000.0;
            // Entrance/exit length: half a second, or a third of a short layer.
            double d = Math.min(0.5, (et - st) / 3);

            List<String> f = new ArrayList<>();
            f.add("format=rgba");
            if (!l.textual()) {
                f.add("scale=w=" + Math.max(2, Math.round(videoWidth * l.widthPct() / 100)) + ":h=-1");
            }
            if (l.opacity() < 1) {
                f.add("colorchannelmixer=aa=" + num(l.opacity()));
            }
            if (!"NONE".equals(l.animation()) && d > 0) {
                f.add("fade=t=in:st=" + num(st) + ":d=" + num(d) + ":alpha=1");
                f.add("fade=t=out:st=" + num(et - d) + ":d=" + num(d) + ":alpha=1");
            }
            graph.add("[" + (i + 1) + ":v]" + String.join(",", f) + "[l" + i + "]");

            String x = "main_w*" + num(l.x()) + "-overlay_w/2";
            String y = "main_h*" + num(l.y()) + "-overlay_h/2";
            String progress = "max(0,1-(t-" + num(st) + ")/" + num(Math.max(d, 0.01)) + ")";
            if ("SLIDE_UP".equals(l.animation())) {
                y = y + "+main_h*0.06*" + progress;
            } else if ("SLIDE_LEFT".equals(l.animation())) {
                x = x + "-(main_w*" + num(l.x()) + "+overlay_w/2)*" + progress;
            }
            String out = i == layers.size() - 1 ? "[vout]" : "[v" + i + "]";
            graph.add(current + "[l" + i + "]overlay=x='" + x + "':y='" + y + "':eval=frame:enable='between(t,"
                    + num(st) + "," + num(et) + ")'" + out);
            current = out;
        }
        return new Graph(String.join(";", graph));
    }

    static String num(double v) {
        BigDecimal d = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
    }
}
