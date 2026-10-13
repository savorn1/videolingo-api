package com.example.videolingo.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Auto-reframe ("Follow the action"): a crop of another shape (e.g. 9:16 out of a 16:9 video) that follows where the
// picture moves over time, instead of one fixed box. MediaTools.motionTrack measures the motion's centre for each step
// of the clip; this smooths that path (a steady camera, not a twitchy one) and turns it into the crop's x/y as
// expressions of t, linear between steps. Pure — tested without decoding video.
public final class ReframeRules {

    /** The shape to cut out (w/h), and the size it ends up (null: as cut). */
    public record Reframe(double aspect) {}

    public static final double MIN_ASPECT = 0.25;
    public static final double MAX_ASPECT = 4;
    /** At most this many steps along the clip, so the expressions stay short; each step is at least a second. */
    static final int MAX_STEPS = 60;
    /** Each step's place is the average over this many steps either side: steady, and (the path being known in advance) no lag. */
    static final int SMOOTH_RADIUS = 1;

    private ReframeRules() {}

    /** Null when usable, else why not. Reframing replaces a drawn crop, and can't follow a turned picture. */
    public static String validate(Reframe r, boolean hasCrop, boolean turned) {
        if (r == null) {
            return null;
        }
        if (r.aspect() < MIN_ASPECT || r.aspect() > MAX_ASPECT) {
            return "Follow the action: pick a shape";
        }
        if (hasCrop) {
            return "Follow the action chooses the crop itself — turn off the drawn crop";
        }
        if (turned) {
            return "Follow the action can't be used with a turn";
        }
        return null;
    }

    /** How long each step is for a clip `clipMs` long: a second, or longer so there are at most MAX_STEPS. */
    public static double stepSeconds(long clipMs) {
        return Math.max(1, Math.ceil(clipMs / 1000.0 / MAX_STEPS));
    }

    /**
     * The path to follow: steps where nothing moved (NaN) keep the nearest known place (the middle when nothing moved
     * at all), then each step is averaged with its neighbours — a steady camera, centred on the action, not trailing it.
     */
    public static double[] smooth(double[] centres) {
        int n = centres.length;
        double[] filled = new double[n];
        double last = Double.NaN;
        for (int i = 0; i < n; i++) {
            filled[i] = Double.isNaN(centres[i]) ? last : centres[i];
            last = filled[i];
        }
        // Steps before the first measurement take the first one; none at all: the middle.
        double first = Double.NaN;
        for (double v : filled) {
            if (!Double.isNaN(v)) {
                first = v;
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(filled[i])) {
                filled[i] = Double.isNaN(first) ? 0.5 : first;
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - SMOOTH_RADIUS); j <= Math.min(n - 1, i + SMOOTH_RADIUS); j++) {
                sum += filled[j];
                count++;
            }
            out[i] = sum / count;
        }
        return out;
    }

    /**
     * The crop's offset along one side as an expression of t: `size` of `full` pixels, centred on each step's
     * position (0–1) and kept inside, linear between steps `step` seconds apart.
     */
    public static String offsetExpression(double[] path, int full, int size, double step) {
        int room = Math.max(0, full - size);
        if (room == 0 || path.length == 0) {
            return "0";
        }
        List<Integer> px = new ArrayList<>();
        for (double c : path) {
            px.add((int) Math.round(Math.min(Math.max(c * full - size / 2.0, 0), room)));
        }
        if (px.stream().distinct().count() == 1) {
            return String.valueOf(px.get(0));
        }
        // if(lt(t,step1), p0+(p1-p0)*t/step, if(lt(t,step2), …)) — held at the last place after the last step.
        StringBuilder expr = new StringBuilder();
        int open = 0;
        for (int i = 0; i + 1 < px.size(); i++) {
            double from = i * step;
            expr.append(String.format(
                    Locale.ROOT,
                    "if(lt(t,%.3f),%d+%d*(t-%.3f)/%.3f,",
                    from + step,
                    px.get(i),
                    px.get(i + 1) - px.get(i),
                    from,
                    step));
            open++;
        }
        expr.append(px.get(px.size() - 1));
        expr.append(")".repeat(open));
        return expr.toString();
    }
}
