package com.example.videolingo.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Recently read storage files kept on local disk, for the editor's on-the-spot reads (waveform,
// auto-center): drawing the waveform at another zoom or centring on another aspect then doesn't
// download the whole video again. A storage key never changes content (a replaced video gets a
// new key), so a cached copy is never stale.
@Component
@Slf4j
public class SourceFileCache {

    /** Files kept at most; the least recently used beyond this go. */
    static final int MAX_FILES = 4;
    /** A file read this recently is never removed, so it can't vanish under a request still opening it. */
    static final Duration IN_USE = Duration.ofMinutes(10);

    private final PipelineSteps steps;
    private final Path dir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public SourceFileCache(PipelineSteps steps, PipelineProperties props) {
        this.steps = steps;
        this.dir = Path.of(props.workDirectory()).resolve("source-cache");
    }

    /** The local copy of `key`, downloaded the first time. Throws JobFailure when storage can't be read. */
    public Path get(String key) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(dir);
                Path file = dir.resolve(fileName(key));
                if (Files.exists(file)) {
                    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now()));
                    return file;
                }
                Path tmp = Files.createTempDirectory(dir, "dl-");
                try {
                    Path downloaded = steps.download(key, tmp, "file");
                    Files.move(downloaded, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    // Only left behind when the download or move failed.
                    try (Stream<Path> left = Files.list(tmp)) {
                        left.forEach(SourceFileCache::deleteQuietly);
                    }
                    deleteQuietly(tmp);
                }
                evict(file);
                return file;
            } catch (IOException e) {
                throw new JobFailure("Couldn't keep a local copy of " + key + ": " + e.getMessage(), e);
            }
        }
    }

    private void evict(Path keep) throws IOException {
        Instant busy = Instant.now().minus(IN_USE);
        List<Path> files;
        try (Stream<Path> all = Files.list(dir)) {
            files = all.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(SourceFileCache::modified).reversed())
                    .toList();
        }
        for (Path f : files.subList(Math.min(MAX_FILES, files.size()), files.size())) {
            if (!f.equals(keep) && modified(f).isBefore(busy)) {
                deleteQuietly(f);
            }
        }
    }

    // Hashed: keys hold slashes and user-chosen names; the extension stays so ffmpeg can tell the format.
    static String fileName(String key) {
        String ext = key.contains(".") ? key.substring(key.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]", "") : "";
        try {
            byte[] hash =
                    java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16) + ext;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("Couldn't remove {}: {}", p, e.getMessage());
        }
    }
}
