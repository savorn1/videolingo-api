package com.example.videolingo.pipeline;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

// Draws a text layer (OverlayRules.Layer) into a transparent PNG at the
// video's own resolution, with Java's 2D graphics — so text works with any
// ffmpeg build (drawtext needs freetype, which many builds leave out) and
// with the fonts installed on the server, which is what `fonts()` lists.
public final class TextRenderer {

    /** Java's logical fonts: always present, mapped to real ones by the JDK. First in the list. */
    public static final List<String> LOGICAL = List.of("SansSerif", "Serif", "Monospaced");

    private static volatile List<String> fonts;

    private TextRenderer() {}

    /** Font families the server can draw with, logical ones first. */
    public static List<String> fonts() {
        List<String> cached = fonts;
        if (cached == null) {
            List<String> list = new ArrayList<>(LOGICAL);
            Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT))
                    .filter(f -> !f.startsWith(".")
                            && !LOGICAL.contains(f)
                            && !f.equals("Dialog")
                            && !f.equals("DialogInput"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(list::add);
            fonts = cached = List.copyOf(list);
        }
        return cached;
    }

    /** CSS-style weight (100–900) → Java's TextAttribute.WEIGHT scale. */
    static float javaWeight(int css) {
        if (css <= 200) {
            return TextAttribute.WEIGHT_EXTRA_LIGHT;
        }
        if (css <= 300) {
            return TextAttribute.WEIGHT_LIGHT;
        }
        if (css <= 400) {
            return TextAttribute.WEIGHT_REGULAR;
        }
        if (css <= 500) {
            return TextAttribute.WEIGHT_MEDIUM;
        }
        if (css <= 600) {
            return TextAttribute.WEIGHT_SEMIBOLD;
        }
        if (css <= 700) {
            return TextAttribute.WEIGHT_BOLD;
        }
        if (css <= 800) {
            return TextAttribute.WEIGHT_EXTRABOLD;
        }
        return TextAttribute.WEIGHT_ULTRABOLD;
    }

    /** The layer drawn at a font size relative to `videoHeight`; trimmed to the text (and its background box). */
    public static BufferedImage render(OverlayRules.Layer layer, int videoHeight) {
        int size = Math.max(6, (int) Math.round(videoHeight * layer.sizePct() / 100));
        Font font = new Font(Map.of(
                TextAttribute.FAMILY, layer.font(),
                TextAttribute.SIZE, (float) size,
                TextAttribute.WEIGHT, javaWeight(layer.weight())));
        String[] lines = layer.text().replace("\r", "").split("\n", -1);

        // Measure on a throwaway graphics context.
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int lineHeight = fm.getAscent() + fm.getDescent();
        int textWidth = 1;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        pg.dispose();

        boolean box = layer.background() != null && layer.backgroundOpacity() > 0;
        int padX = box ? Math.round(size * 0.45f) : Math.round(size * 0.1f);
        int padY = box ? Math.round(size * 0.25f) : Math.round(size * 0.1f);
        int width = textWidth + padX * 2;
        int height = lineHeight * lines.length + padY * 2;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (box) {
            Color bg = hex(layer.background());
            g.setColor(new Color(
                    bg.getRed(), bg.getGreen(), bg.getBlue(), (int) Math.round(layer.backgroundOpacity() * 255)));
            int radius = Math.round(size * 0.5f);
            g.fillRoundRect(0, 0, width, height, radius, radius);
        }
        g.setFont(font);
        g.setColor(hex(layer.color()));
        for (int i = 0; i < lines.length; i++) {
            int w = g.getFontMetrics().stringWidth(lines[i]);
            int x = switch (layer.align()) {
                case "LEFT" -> padX;
                case "RIGHT" -> width - padX - w;
                default -> (width - w) / 2;
            };
            g.drawString(lines[i], x, padY + i * lineHeight + fm.getAscent());
        }
        g.dispose();
        return img;
    }

    public static void write(BufferedImage image, Path file) {
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new JobFailure("Couldn't write the text image: " + e.getMessage(), e);
        }
    }

    static Color hex(String value) {
        return new Color(Integer.parseInt(value.substring(1), 16));
    }
}
