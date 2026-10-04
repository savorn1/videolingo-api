package com.example.videolingo.pipeline;

// The "Auto-center" crop suggestion: given where the motion is in a video
// (a coarse heatmap MediaTools builds by frame-differencing) and a target
// shape, where to put the crop box. Pure — the heatmap math and the
// box-fitting are both tested without decoding real video.
public final class AutoCropRules {

    public record Centroid(double x, double y, double confidence) {}

    public record CropRect(int x, int y, int w, int h) {}

    private AutoCropRules() {}

    /**
     * The weighted centre of a `cols`×`rows` motion heatmap, as fractions
     * (0–1) of the frame. Cells are expected already normalised to 0–1 (the
     * busiest cell = 1), so `confidence` — the average cell value — is
     * itself 0–1: near 0 for a mostly static video, where the centre is the
     * safest guess anyway.
     */
    public static Centroid centroid(float[] heatmap, int cols, int rows) {
        double sum = 0;
        double wx = 0;
        double wy = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                float v = heatmap[j * cols + i];
                sum += v;
                wx += v * (i + 0.5) / cols;
                wy += v * (j + 0.5) / rows;
            }
        }
        if (sum < 1e-6) {
            return new Centroid(0.5, 0.5, 0);
        }
        return new Centroid(clamp01(wx / sum), clamp01(wy / sum), Math.min(1, sum / (cols * rows)));
    }

    /**
     * The largest box of `aspect` (w/h) that fits inside the frame, centred
     * on (centerX, centerY) — fractions of the frame — and pulled back
     * inside the frame's edges if that would run off them. Same fitting
     * logic as the crop editor's own "pick a shape" (VideoClipEditor.setAspect).
     */
    public static CropRect suggestCrop(double aspect, int videoWidth, int videoHeight, double centerX, double centerY) {
        double w = videoWidth;
        double h = w / aspect;
        if (h > videoHeight) {
            h = videoHeight;
            w = h * aspect;
        }
        double cx = centerX * videoWidth;
        double cy = centerY * videoHeight;
        double x = Math.min(Math.max(cx - w / 2, 0), videoWidth - w);
        double y = Math.min(Math.max(cy - h / 2, 0), videoHeight - h);
        return new CropRect((int) Math.round(x), (int) Math.round(y), (int) Math.round(w), (int) Math.round(h));
    }

    private static double clamp01(double v) {
        return Math.min(1, Math.max(0, v));
    }
}
