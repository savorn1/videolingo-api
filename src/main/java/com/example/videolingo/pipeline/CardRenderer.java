package com.example.videolingo.pipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

// The picture on an intro/outro card: the text (drawn by TextRenderer, so the same
// fonts as text layers) with the logo, if any, above it — one transparent PNG that
// ffmpeg lays over the card's background colour.
public final class CardRenderer {

    /** The text is this % of the video's height; the logo at most this % of its width. */
    static final double TEXT_PCT = 7;

    static final double LOGO_WIDTH_PCT = 25;

    private CardRenderer() {}

    /** The text as a centred, bold layer in `color`, wrapped by TextRenderer to the frame. */
    static OverlayRules.Layer textLayer(String text, String color) {
        return new OverlayRules.Layer(
                "TEXT",
                text.strip(),
                "SansSerif",
                700,
                TEXT_PCT,
                color,
                null,
                0,
                "CENTER",
                null,
                0,
                0.5,
                0.5,
                1,
                0,
                null,
                "NONE");
    }

    /** The logo (scaled to fit) over the text, centred, with a gap between; either may be missing. */
    static BufferedImage compose(BufferedImage text, BufferedImage logo, int videoWidth, int videoHeight) {
        BufferedImage scaledLogo =
                logo == null ? null : scaleToFit(logo, (int) (videoWidth * LOGO_WIDTH_PCT / 100), videoHeight / 3);
        int gap = scaledLogo != null && text != null ? Math.max(4, videoHeight / 30) : 0;
        List<BufferedImage> parts = java.util.stream.Stream.of(scaledLogo, text)
                .filter(java.util.Objects::nonNull)
                .toList();
        int width = Math.max(
                1, parts.stream().mapToInt(BufferedImage::getWidth).max().orElse(1));
        int height =
                Math.max(1, parts.stream().mapToInt(BufferedImage::getHeight).sum() + gap);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int y = 0;
        for (BufferedImage part : parts) {
            g.drawImage(part, (width - part.getWidth()) / 2, y, null);
            y += part.getHeight() + gap;
        }
        g.dispose();
        return out;
    }

    /** Shrinks (never enlarges) to fit within maxW × maxH, keeping the shape. */
    static BufferedImage scaleToFit(BufferedImage img, int maxW, int maxH) {
        double k = Math.min(1, Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight()));
        int w = Math.max(1, (int) Math.round(img.getWidth() * k));
        int h = Math.max(1, (int) Math.round(img.getHeight() * k));
        if (w == img.getWidth() && h == img.getHeight()) {
            return img;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
