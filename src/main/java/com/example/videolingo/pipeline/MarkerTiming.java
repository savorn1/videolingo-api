package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

// Where a moment of a video lands in a result made from it, worked out from the job's settings — so its
// markers (and chapters) can move along when the result replaces the video or becomes a new one. Null = the
// moment isn't in the result. Pure, so tested.
final class MarkerTiming {

    private MarkerTiming() {}

    /** The timing for a job's stored settings; null for a job whose timing isn't known. */
    static LongFunction<Long> forJob(JsonNode p, ObjectMapper reader, Integer segmentIndex, Long resultMs)
            throws Exception {
        String op = p.path("operation").asText("");
        switch (op) {
            case "TRIM" -> {
                EditJobParams.Trim t = reader.treeToValue(p, EditJobParams.Trim.class);
                return at -> trim(t, at, resultMs);
            }
            case "CUT" -> {
                EditJobParams.Cut c = reader.treeToValue(p, EditJobParams.Cut.class);
                List<VideoEditRules.Segment> cuts = c.cuts() == null
                        ? List.of()
                        : c.cuts().stream()
                                .map(r -> new VideoEditRules.Segment(r.startMs(), r.endMs()))
                                .toList();
                return at -> cut(cuts, at);
            }
            case "SPLIT" -> {
                EditJobParams.Split s = reader.treeToValue(p, EditJobParams.Split.class);
                if (segmentIndex == null
                        || s.segments() == null
                        || segmentIndex >= s.segments().size()) {
                    return null;
                }
                EditJobParams.SplitPart part = s.segments().get(segmentIndex);
                return at -> range(part.startMs(), part.endMs(), at);
            }
            case "AUDIO" -> {
                double speed = p.path("audio").path("speed").asDouble(1);
                double s = speed > 0 ? speed : 1;
                return at -> Math.round(at / s);
            }
            case "OVERLAY" -> {
                return at -> at;
            }
            default -> {
                return null;
            }
        }
    }

    /** Inside [start, end) → its time from the start; else null. */
    static Long range(long startMs, Long endMs, long atMs) {
        return atMs < startMs || (endMs != null && atMs >= endMs) ? null : atMs - startMs;
    }

    /** With the (merged, sorted) cuts taken out: null inside one, else moved back by the time cut before it. */
    static Long cut(List<VideoEditRules.Segment> cuts, long atMs) {
        long removed = 0;
        for (VideoEditRules.Segment c : cuts) {
            if (atMs < c.startMs()) {
                break;
            }
            if (c.endMs() == null || atMs < c.endMs()) {
                return null;
            }
            removed += c.endMs() - c.startMs();
        }
        return atMs - removed;
    }

    /**
     * Through a trim, in the order the job does it: the range, the parts at another speed, the held frames and
     * pauses (a moment at a hold stays before it), played backwards (needs the result's length), then the intro card.
     */
    static Long trim(EditJobParams.Trim t, long atMs, Long resultMs) {
        Long x = range(t.startMs(), t.endMs(), atMs);
        if (x == null) {
            return null;
        }
        List<VideoEditRules.SpeedRange> parts = VideoEditRules.speedParts(t.speed(), t.speeds());
        long y = parts.isEmpty() ? x : VideoEditRules.rampedAcross(x, parts);
        List<long[]> holds = new ArrayList<>();
        if (t.freeze() != null) {
            holds.add(new long[] {
                VideoEditRules.rampedAcross(t.freeze().atMs(), parts),
                t.freeze().durationMs()
            });
        }
        if (t.holds() != null) {
            t.holds()
                    .forEach(h -> holds.add(new long[] {VideoEditRules.rampedAcross(h.atMs(), parts), h.durationMs()}));
        }
        holds.sort((a, b) -> Long.compare(a[0], b[0]));
        long held = 0;
        for (long[] h : VideoEditRules.spacedHolds(holds)) {
            if (h[0] < y) {
                held += h[1];
            }
        }
        y += held;
        long intro = t.cards() != null && t.cards().intro() != null
                ? t.cards().intro().durationMs()
                : 0;
        long outro = t.cards() != null && t.cards().outro() != null
                ? t.cards().outro().durationMs()
                : 0;
        if (Boolean.TRUE.equals(t.reverse())) {
            if (resultMs == null) {
                return null;
            }
            y = Math.max(0, resultMs - intro - outro - y);
        }
        return y + intro;
    }

    /** The time if it is inside a result `lengthMs` long (unknown = any), else null. */
    static Long within(Long atMs, Long lengthMs) {
        return atMs == null || atMs < 0 || (lengthMs != null && atMs >= lengthMs) ? null : atMs;
    }
}
