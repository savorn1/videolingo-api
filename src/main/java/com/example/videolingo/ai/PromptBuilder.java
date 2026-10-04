package com.example.videolingo.ai;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import java.util.List;

// Prompt text for the AI features. The system prompt is two blocks:
//   1. stable instructions (identical for every video)
//   2. the video's transcript, marked cache_control
// so every generation and chat turn about the same video shares one cached
// prefix; the task-specific instructions go in the user message, after it.
public final class PromptBuilder {

    private PromptBuilder() {}

    public record Segment(long startMs, long endMs, String text, String speaker) {}

    public record VideoContext(
            String title, String languageName, String languageCode, Integer durationSeconds, List<Segment> segments) {}

    static final String BASE_INSTRUCTIONS = """
            You help language learners study videos on VideoLingo, a platform for learning languages through video.
            You are given the transcript of one video, with a [m:ss] timestamp on each line.
            Base everything you write on what the transcript actually says. Do not add facts that are not in it. \
            When you give a time, use the timestamps from the transcript.""";

    /** "[1:02] Ana: text" per line; hours appear only when needed ("[1:02:03]"). */
    public static String formatTranscript(List<Segment> segments) {
        StringBuilder out = new StringBuilder();
        for (Segment s : segments) {
            out.append('[').append(timestamp(s.startMs())).append("] ");
            if (s.speaker() != null && !s.speaker().isBlank()) {
                out.append(s.speaker().strip()).append(": ");
            }
            out.append(s.text().replace('\n', ' ').strip()).append('\n');
        }
        return out.toString();
    }

    static String timestamp(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    public static List<TextBlockParam> system(VideoContext video) {
        String header = "Video: \"" + video.title() + "\"\nSpoken language: " + video.languageName() + " ("
                + video.languageCode() + ")"
                + (video.durationSeconds() != null ? "\nLength: " + timestamp(video.durationSeconds() * 1000L) : "");
        String transcript = header + "\n\n<transcript>\n" + formatTranscript(video.segments()) + "</transcript>";
        return List.of(
                TextBlockParam.builder().text(BASE_INSTRUCTIONS).build(),
                TextBlockParam.builder()
                        .text(transcript)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build());
    }

    public static String chatSystemNote() {
        return """
                You are now chatting with a learner about this video. Answer in the language they write in unless they ask \
                otherwise. Keep answers short and concrete, quote the transcript when it helps, and say so plainly if the \
                video does not cover what they ask.""";
    }

    public static String task(AiTask task, String outputLanguageName, int count) {
        String lang = "Write your answer in " + outputLanguageName + ".";
        return switch (task) {
            case SUMMARY -> "Summarize this video for a learner. " + lang;
            case CHAPTERS ->
                "Split this video into chapters where the topic changes — usually 3 to 10, fewer for a short video. "
                        + lang;
            case KEY_POINTS ->
                "List the " + count + " most important points a learner should take away from this video, in order. "
                        + lang;
            case QUESTIONS ->
                "Write " + count + " open-ended comprehension questions about this video with model answers, "
                        + "mixing easy, medium and hard. " + lang;
            case QUIZ ->
                "Write a " + count + "-question multiple-choice quiz that checks a learner understood this video. "
                        + "Each question has 3 or 4 options with exactly one correct answer; wrong options must be plausible. "
                        + "Vary which position holds the correct answer. " + lang;
        };
    }

    public enum AiTask {
        SUMMARY,
        CHAPTERS,
        KEY_POINTS,
        QUESTIONS,
        QUIZ
    }
}
