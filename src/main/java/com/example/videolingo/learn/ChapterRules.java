package com.example.videolingo.learn;

import com.example.videolingo.entity.VideoMarker;
import java.util.ArrayList;
import java.util.List;

// The chapters learners see on the watch page: the video's markers flagged as chapters, in time order,
// within the video, one per moment.
public final class ChapterRules {

    public record Chapter(long atMs, String label) {}

    /** More than this many is no longer a table of contents. */
    public static final int MAX_CHAPTERS = 100;

    private ChapterRules() {}

    public static List<Chapter> fromMarkers(List<VideoMarker> markers, Long durationMs) {
        List<Chapter> out = new ArrayList<>();
        markers.stream()
                .filter(VideoMarker::isChapter)
                .filter(m -> m.getAtMs() >= 0 && (durationMs == null || durationMs <= 0 || m.getAtMs() < durationMs))
                .sorted(java.util.Comparator.comparingLong(VideoMarker::getAtMs))
                .forEach(m -> {
                    if (out.size() < MAX_CHAPTERS
                            && (out.isEmpty() || out.get(out.size() - 1).atMs() != m.getAtMs())) {
                        out.add(new Chapter(m.getAtMs(), m.getLabel()));
                    }
                });
        return out;
    }
}
