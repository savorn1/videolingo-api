package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import com.example.videolingo.dto.TranscriptWord;
import java.util.function.LongFunction;

// Where a moment of a video lands in a result made from it, worked out from the job's settings — so its
// markers (and chapters) can move along when the result replaces the video or becomes a new one. Null = the
// moment isn't in the result. Pure, so tested.
public final class MarkerTiming {

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
    public static Long within(Long atMs, Long lengthMs) {
        return atMs == null || atMs < 0 || (lengthMs != null && atMs >= lengthMs) ? null : atMs;
    }

    /** A line of a transcript, with its words' timings when known. */
    public record TimedLine(long startMs, long endMs, String text, String speaker, List<TranscriptWord> words) {}

    /** How finely a line is sampled to find what of it is left in the result. */
    static final long SAMPLE_STEP_MS = 50;

    /** A line left shorter than this in the result is dropped. */
    static final long MIN_LINE_MS = 200;

    /**
     * A transcript's lines on the result's timeline: each line spans what is left of it (sampled, so a line crossing a
     * cut or the trim's edge keeps only its part inside), its words moved the same way (ones cut out dropped), in
     * time order; lines with almost nothing left are dropped. Null when the result runs backwards — its lines
     * wouldn't make sense.
     */
    public static List<TimedLine> retime(List<TimedLine> lines, LongFunction<Long> timing, Long lengthMs) {
        List<TimedLine> out = new ArrayList<>();
        for (TimedLine line : lines) {
            Long from = null;
            Long to = null;
            Long first = null;
            Long last = null;
            for (long t = line.startMs(); ; t = Math.min(t + SAMPLE_STEP_MS, line.endMs())) {
                Long at = within(timing.apply(t), lengthMs);
                if (at != null) {
                    if (first == null) {
                        first = at;
                    }
                    last = at;
                    from = from == null ? at : Math.min(from, at);
                    to = to == null ? at : Math.max(to, at);
                }
                if (t >= line.endMs()) {
                    break;
                }
            }
            if (first == null || to - from < MIN_LINE_MS) {
                continue;
            }
            if (last < first) {
                return null;
            }
            List<TranscriptWord> words = null;
            if (line.words() != null) {
                words = new ArrayList<>();
                for (TranscriptWord w : line.words()) {
                    Long ws = within(timing.apply(w.startMs()), lengthMs);
                    Long we = within(timing.apply(Math.max(w.startMs(), w.endMs() - 1)), lengthMs);
                    if (ws != null && we != null && we >= ws) {
                        words.add(new TranscriptWord(ws, Math.max(ws + 1, we + 1), w.text()));
                    }
                }
                if (words.isEmpty()) {
                    words = null;
                }
            }
            out.add(new TimedLine(from, to, line.text(), line.speaker(), words));
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
