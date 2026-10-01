package com.example.videolingo.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the processing pipeline (see application.properties, prefix
// "pipeline"). Every external service is optional: a job that needs one that
// isn't configured fails with a message saying what to set.
@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(
        // Master switch for the job worker.
        Boolean enabled,
        // How often the worker looks for queued jobs.
        Long pollMs,
        // Command-line tools. yt-dlp fetches audio from YouTube/Vimeo/Facebook
        // links; ffmpeg converts and splits audio and encodes the dub track.
        String ytDlpPath,
        String ffmpegPath,
        // Scratch space for downloads; defaults to the system temp directory.
        String workDir,
        // Longest media a job will download, in minutes.
        Integer maxMediaMinutes,
        // Speech-to-text (OpenAI Whisper).
        String openaiApiKey,
        String openaiBaseUrl,
        String whisperModel,
        // Translation (OpenAI chat model, same key as Whisper).
        String translationModel,
        // Text-to-speech (OpenAI speech API, same key as Whisper).
        String ttsModel,
        // How many jobs of a lane may run at once (default 1; see JobLane). ffmpeg is heavy on CPU and memory, so raise the media lane with care.
        Integer mediaConcurrency,
        Integer aiConcurrency
) {

    /** The most jobs of one lane that run at the same time: 1 unless configured, never more than {@link #MAX_CONCURRENCY}. */
    public static final int MAX_CONCURRENCY = 4;

    public int concurrency(JobLane lane) {
        Integer configured = lane == JobLane.MEDIA ? mediaConcurrency : aiConcurrency;
        return configured == null || configured < 1 ? 1 : Math.min(configured, MAX_CONCURRENCY);
    }


    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public long poll() {
        return pollMs == null || pollMs < 500 ? 5000 : pollMs;
    }

    public String ytDlp() {
        return blank(ytDlpPath) ? "yt-dlp" : ytDlpPath;
    }

    public String ffmpeg() {
        return blank(ffmpegPath) ? "ffmpeg" : ffmpegPath;
    }

    public String workDirectory() {
        return blank(workDir) ? System.getProperty("java.io.tmpdir") : workDir;
    }

    public int maxMinutes() {
        return maxMediaMinutes == null || maxMediaMinutes <= 0 ? 180 : maxMediaMinutes;
    }

    public String openaiBase() {
        return blank(openaiBaseUrl) ? "https://api.openai.com/v1" : openaiBaseUrl.replaceAll("/+$", "");
    }

    public String whisper() {
        return blank(whisperModel) ? "whisper-1" : whisperModel;
    }

    public String translationModel() {
        return blank(translationModel) ? "gpt-4.1" : translationModel;
    }

    public boolean translationReady() {
        return !blank(openaiApiKey);
    }

    public String ttsModel() {
        return blank(ttsModel) ? "tts-1" : ttsModel;
    }

    public boolean speechToTextReady() {
        return !blank(openaiApiKey);
    }

    public boolean textToSpeechReady() {
        return !blank(openaiApiKey);
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
