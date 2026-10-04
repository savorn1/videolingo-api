package com.example.videolingo.learn;

import com.example.videolingo.exception.AppException;
import com.example.videolingo.pipeline.JobFailure;
import com.example.videolingo.pipeline.PipelineProperties;
import com.example.videolingo.pipeline.WhisperClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

// Shadowing practice: what speech-to-text heard in a learner's recording of
// one line. The comparison with the line itself happens in the browser (the
// same word-by-word check dictation uses), so this only transcribes.
@Service
@RequiredArgsConstructor
public class PronunciationService {

    /** ~30 s of browser-recorded Opus/AAC is well under this. */
    static final long MAX_BYTES = 3L * 1024 * 1024;

    public record Heard(String text) {}

    private final WhisperClient whisper;
    private final PipelineProperties props;

    public Heard transcribe(MultipartFile file, String language) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Record yourself first");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Recordings can be at most about 30 seconds");
        }
        if (!props.speechToTextReady()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "Pronunciation checks aren't set up on this server");
        }
        String ext = extension(file.getContentType());
        if (ext == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "That recording format isn't supported");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("pronunciation-", ext);
            file.transferTo(tmp);
            WhisperClient.Result result = whisper.transcribe(tmp, language);
            String text = result.segments().stream()
                    .map(WhisperClient.Segment::text)
                    .map(String::strip)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.joining(" "));
            return new Heard(text);
        } catch (JobFailure e) {
            throw new AppException(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (IOException e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Couldn't read the recording");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Temp space.
                }
            }
        }
    }

    // Whisper tells formats apart by the file name.
    static String extension(String contentType) {
        String type =
                contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "audio/webm", "video/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/mp4", "audio/x-m4a", "audio/aac" -> ".m4a";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            default -> null;
        };
    }
}
