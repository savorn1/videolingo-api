package com.example.videolingo.subtitle;

import com.example.videolingo.dto.TranscriptWord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Word timings in transcripts: which segment each word speech-to-text timed belongs to, and which words a saved
// segment can keep. Pure — tested without the API or the database.
public final class WordTimings {

    /** A word may run this far outside its segment and still be kept (the two timings are rounded separately). */
    static final long SLACK_MS = 250;

    private WordTimings() {}

    /** Each segment's words: a word goes to the segment its middle falls in (or the nearest one before). */
    public static List<List<TranscriptWord>> assign(List<long[]> segmentTimes, List<TranscriptWord> words) {
        List<List<TranscriptWord>> out = new ArrayList<>();
        segmentTimes.forEach(t -> out.add(new ArrayList<>()));
        if (segmentTimes.isEmpty()) {
            return out;
        }
        for (TranscriptWord w : words) {
            if (w.text() == null || w.text().isBlank()) {
                continue;
            }
            long mid = (w.startMs() + w.endMs()) / 2;
            int at = 0;
            for (int i = 0; i < segmentTimes.size(); i++) {
                if (segmentTimes.get(i)[0] <= mid) {
                    at = i;
                }
            }
            out.get(at)
                    .add(new TranscriptWord(
                            w.startMs(),
                            Math.max(w.startMs() + 1, w.endMs()),
                            w.text().strip()));
        }
        return out;
    }

    /** The words worth keeping for a segment: inside it (give or take SLACK_MS), in time order; null when none. */
    public static List<TranscriptWord> clean(List<TranscriptWord> words, long startMs, long endMs) {
        if (words == null) {
            return null;
        }
        List<TranscriptWord> kept = words.stream()
                .filter(Objects::nonNull)
                .filter(w -> w.text() != null && !w.text().isBlank())
                .filter(w ->
                        w.startMs() >= startMs - SLACK_MS && w.endMs() <= endMs + SLACK_MS && w.endMs() > w.startMs())
                .sorted(java.util.Comparator.comparingLong(TranscriptWord::startMs))
                .toList();
        return kept.isEmpty() ? null : kept;
    }

    /**
     * When a transcript is saved by hand, a segment keeps the word timings it had only if it is the same segment —
     * same times, same text. An edited segment's old timings would point at the wrong words.
     */
    public static String key(long startMs, long endMs, String text) {
        return startMs + ":" + endMs + ":" + (text == null ? "" : text.strip());
    }
}
