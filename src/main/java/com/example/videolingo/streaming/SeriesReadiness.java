package com.example.videolingo.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Whether a streaming collection is live on RuengFlix and what to fix first. Pure and static, so it's
// unit-tested; the episode rules match movie-api's (src/catalog/rules.ts there): an episode is a video
// that is enabled, PUBLIC, not archived and not in the trash, numbered 1, 2, 3… in collection order.
public final class SeriesReadiness {

    public enum State {
        LIVE,
        NOT_PUBLIC,
        NO_EPISODES
    }

    public enum Level {
        ERROR,
        WARNING
    }

    public record Problem(Level level, String message) {}

    /** What the checks need to know about a series. */
    public record Input(
            String visibility,
            String category,
            String coverUrl,
            String bannerUrl,
            String verticalUrl,
            boolean vip,
            int freeEpisodes,
            Integer featuredRank,
            // Released episodes in order: whether each has a subtitle track movie-api would serve
            List<Boolean> episodeHasSubtitles,
            // Videos in the collection that are neither released nor scheduled, so the site skips them
            int skippedVideos,
            int scheduledVideos) {}

    public record Result(State state, List<Problem> problems) {}

    private SeriesReadiness() {}

    public static Result check(Input in) {
        List<Problem> problems = new ArrayList<>();
        int episodes = in.episodeHasSubtitles().size();

        State state;
        if (!"PUBLIC".equals(in.visibility())) {
            state = State.NOT_PUBLIC;
            problems.add(new Problem(
                    Level.ERROR,
                    "Visibility is " + label(in.visibility()) + ": only Public collections appear on RuengFlix"));
        } else if (episodes == 0) {
            state = State.NO_EPISODES;
        } else {
            state = State.LIVE;
        }
        if (episodes == 0) {
            problems.add(new Problem(
                    Level.ERROR,
                    in.scheduledVideos() > 0
                            ? "No released episodes yet (" + in.scheduledVideos() + " scheduled)"
                            : "No released episodes: enable a Public video in this collection"));
        }

        if (in.category() == null || in.category().isBlank()) {
            problems.add(new Problem(Level.WARNING, "No category: the site files it under Fantasy"));
        }
        if (blank(in.coverUrl())) {
            problems.add(new Problem(Level.WARNING, "No cover image: the site shows placeholder artwork"));
        }
        if (in.featuredRank() != null && blank(in.bannerUrl())) {
            problems.add(new Problem(Level.WARNING, "Hero pick without a banner image: the hero stretches the cover"));
        }
        if (blank(in.verticalUrl())) {
            problems.add(new Problem(Level.WARNING, "No vertical image: Shorts crop the cover"));
        }

        List<Integer> unsubtitled = new ArrayList<>();
        for (int i = 0; i < episodes; i++) {
            if (!Boolean.TRUE.equals(in.episodeHasSubtitles().get(i))) {
                unsubtitled.add(i + 1);
            }
        }
        if (!unsubtitled.isEmpty()) {
            problems.add(new Problem(
                    Level.WARNING,
                    (unsubtitled.size() == 1 ? "Episode " : "Episodes ") + ranges(unsubtitled)
                            + " " + (unsubtitled.size() == 1 ? "has" : "have")
                            + " no published Khmer or default subtitles"));
        }
        if (in.skippedVideos() > 0) {
            problems.add(new Problem(
                    Level.WARNING,
                    in.skippedVideos() + (in.skippedVideos() == 1 ? " video is" : " videos are")
                            + " skipped: disabled, not Public, archived or in the trash"));
        }
        if (in.vip() && episodes > 0 && in.freeEpisodes() >= episodes) {
            problems.add(new Problem(
                    Level.WARNING, "VIP is on, but all " + episodes + " episodes are within the free ones"));
        }
        return new Result(state, List.copyOf(problems));
    }

    /** [1, 2, 3, 5, 7, 8] → "1–3, 5, 7–8" */
    static String ranges(List<Integer> sorted) {
        List<String> parts = new ArrayList<>();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i <= sorted.size(); i++) {
            Integer n = i < sorted.size() ? sorted.get(i) : null;
            if (n != null && n == prev + 1) {
                prev = n;
                continue;
            }
            parts.add(start == prev ? String.valueOf(start) : start + "–" + prev);
            if (n != null) {
                start = n;
                prev = n;
            }
        }
        return parts.stream().collect(Collectors.joining(", "));
    }

    private static String label(String visibility) {
        if (visibility == null || visibility.isBlank()) return "not set";
        return visibility.charAt(0) + visibility.substring(1).toLowerCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
