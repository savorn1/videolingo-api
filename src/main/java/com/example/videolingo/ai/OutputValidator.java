package com.example.videolingo.ai;

import com.example.videolingo.ai.schema.ChaptersOutput;
import com.example.videolingo.ai.schema.KeyPointsOutput;
import com.example.videolingo.ai.schema.QuestionsOutput;
import com.example.videolingo.ai.schema.QuizOutput;
import com.example.videolingo.ai.schema.SummaryOutput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Structured outputs guarantee the JSON *shape*; these checks cover what a
// schema can't — timestamps inside the video, a correct answer that exists,
// no duplicate options. Items that can't be repaired are dropped with a
// warning rather than failing the whole generation.
public final class OutputValidator {

    private OutputValidator() {}

    public record Checked<T>(T value, List<String> warnings) {}

    static int clampSeconds(int seconds, long durationMs) {
        int max = durationMs > 0 ? (int) (durationMs / 1000) : Integer.MAX_VALUE;
        return Math.max(0, Math.min(seconds, max));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static Checked<SummaryOutput> summary(SummaryOutput in) {
        List<String> warnings = new ArrayList<>();
        if (blank(in.summary())) {
            warnings.add("The summary came back empty");
        }
        List<String> topics = in.topics() == null
                ? List.of()
                : in.topics().stream()
                        .filter(t -> !blank(t))
                        .map(String::strip)
                        .distinct()
                        .toList();
        return new Checked<>(
                new SummaryOutput(strip(in.tldr()), strip(in.summary()), topics, in.estimatedLevel()), warnings);
    }

    public static Checked<ChaptersOutput> chapters(ChaptersOutput in, long durationMs) {
        List<String> warnings = new ArrayList<>();
        List<ChaptersOutput.Chapter> out = new ArrayList<>();
        Set<Integer> starts = new HashSet<>();
        List<ChaptersOutput.Chapter> sorted = new ArrayList<>(in.chapters() == null ? List.of() : in.chapters());
        sorted.sort(Comparator.comparingInt(ChaptersOutput.Chapter::startSeconds));
        for (ChaptersOutput.Chapter c : sorted) {
            if (blank(c.title())) {
                warnings.add("Dropped a chapter with no title");
                continue;
            }
            // A chapter can't begin at or after the end (clamping would stack it on the last second).
            if (durationMs > 0 && c.startSeconds() >= durationMs / 1000 && !out.isEmpty()) {
                warnings.add("Dropped chapter \"" + c.title().strip() + "\": it starts after the video ends");
                continue;
            }
            int start = clampSeconds(c.startSeconds(), durationMs);
            if (!starts.add(start)) {
                warnings.add("Dropped chapter \"" + c.title().strip() + "\": another chapter starts at the same time");
                continue;
            }
            out.add(new ChaptersOutput.Chapter(start, c.title().strip(), strip(c.summary())));
        }
        // Chapters must cover the whole video: the first one starts at 0.
        if (!out.isEmpty() && out.get(0).startSeconds() != 0) {
            ChaptersOutput.Chapter first = out.get(0);
            out.set(0, new ChaptersOutput.Chapter(0, first.title(), first.summary()));
            warnings.add("Moved the first chapter to start at 0:00");
        }
        return new Checked<>(new ChaptersOutput(out), warnings);
    }

    public static Checked<KeyPointsOutput> keyPoints(KeyPointsOutput in, long durationMs) {
        List<String> warnings = new ArrayList<>();
        List<KeyPointsOutput.KeyPoint> out = new ArrayList<>();
        for (KeyPointsOutput.KeyPoint k :
                in.keyPoints() == null ? List.<KeyPointsOutput.KeyPoint>of() : in.keyPoints()) {
            if (blank(k.point())) {
                warnings.add("Dropped an empty key point");
                continue;
            }
            out.add(new KeyPointsOutput.KeyPoint(
                    k.point().strip(), strip(k.explanation()), clampSeconds(k.timestampSeconds(), durationMs)));
        }
        return new Checked<>(new KeyPointsOutput(out), warnings);
    }

    public static Checked<QuestionsOutput> questions(QuestionsOutput in, long durationMs) {
        List<String> warnings = new ArrayList<>();
        List<QuestionsOutput.Question> out = new ArrayList<>();
        for (QuestionsOutput.Question q :
                in.questions() == null ? List.<QuestionsOutput.Question>of() : in.questions()) {
            if (blank(q.question()) || blank(q.answer())) {
                warnings.add("Dropped a question without " + (blank(q.question()) ? "text" : "an answer"));
                continue;
            }
            out.add(new QuestionsOutput.Question(
                    q.question().strip(),
                    q.answer().strip(),
                    q.difficulty() == null ? QuestionsOutput.Difficulty.MEDIUM : q.difficulty(),
                    clampSeconds(q.timestampSeconds(), durationMs)));
        }
        return new Checked<>(new QuestionsOutput(out), warnings);
    }

    public static Checked<QuizOutput> quiz(QuizOutput in, long durationMs) {
        List<String> warnings = new ArrayList<>();
        List<QuizOutput.QuizQuestion> out = new ArrayList<>();
        int n = 0;
        for (QuizOutput.QuizQuestion q : in.questions() == null ? List.<QuizOutput.QuizQuestion>of() : in.questions()) {
            n++;
            if (blank(q.question())) {
                warnings.add("Quiz question " + n + " dropped: no question text");
                continue;
            }
            List<String> options = q.options() == null
                    ? List.of()
                    : q.options().stream().map(o -> o == null ? "" : o.strip()).toList();
            if (options.size() < 2 || options.stream().anyMatch(String::isEmpty)) {
                warnings.add("Quiz question " + n + " dropped: it needs at least two non-empty options");
                continue;
            }
            Set<String> seen = new HashSet<>();
            if (options.stream().anyMatch(o -> !seen.add(o.toLowerCase(Locale.ROOT)))) {
                warnings.add("Quiz question " + n + " dropped: two options are the same");
                continue;
            }
            if (q.correctOptionIndex() < 0 || q.correctOptionIndex() >= options.size()) {
                warnings.add("Quiz question " + n + " dropped: its correct answer doesn't match any option");
                continue;
            }
            out.add(new QuizOutput.QuizQuestion(
                    q.question().strip(),
                    options,
                    q.correctOptionIndex(),
                    strip(q.explanation()),
                    clampSeconds(q.timestampSeconds(), durationMs)));
        }
        return new Checked<>(new QuizOutput(out), warnings);
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
