package com.example.videolingo.ingest;

import com.example.videolingo.pipeline.AudioEditRules;
import com.example.videolingo.pipeline.OverlayRules;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Large uploads go to storage in parts (S3 multipart), so a dropped connection costs one part, not the whole
 * file: the browser starts one, asks for each part's signed URL as it goes, and the server puts the parts
 * together at the end from what storage holds.
 */
public final class MultipartRules {

    private MultipartRules() {}

    /** Files at least this big are sent in parts (the UI mirrors it as MULTIPART_THRESHOLD). */
    public static final long THRESHOLD_BYTES = 64L * 1024 * 1024;

    /** The usual part: big enough that a 2 GB file is ~128 parts, small enough that a retry is quick. */
    public static final long PART_BYTES = 16L * 1024 * 1024;

    /** S3's limits: at most 10,000 parts, each (but the last) at least 5 MiB. */
    static final int MAX_PARTS = 10_000;

    static final long MIN_PART_BYTES = 5L * 1024 * 1024;

    /** Keys the upload endpoints hand out: a known folder and a fresh UUID name — never an existing video's key. */
    private static final List<String> PREFIXES =
            List.of("videos/", "thumbnails/", AudioEditRules.UPLOAD_PREFIX, OverlayRules.UPLOAD_PREFIX);

    private static final Pattern NAME =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{2,5}");

    /** The part size for a file: PART_BYTES, or larger when the file would otherwise need more than 10,000 parts. */
    public static long partSize(long size) {
        long needed = (size + MAX_PARTS - 1) / MAX_PARTS;
        return Math.max(PART_BYTES, Math.max(MIN_PART_BYTES, needed));
    }

    public static int partCount(long size, long partSize) {
        return (int) Math.max(1, (size + partSize - 1) / partSize);
    }

    /** Bytes in part {@code partNumber} (1-based) of a {@code size}-byte file; 0 when there is no such part. */
    public static long partLength(long size, long partSize, int partNumber) {
        if (partNumber < 1 || partNumber > partCount(size, partSize)) {
            return 0;
        }
        long start = (partNumber - 1L) * partSize;
        return Math.min(partSize, size - start);
    }

    /** Whether a key is one the upload endpoints could have made. */
    public static boolean isUploadKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)
                    && NAME.matcher(key.substring(prefix.length())).matches()) {
                return true;
            }
        }
        return false;
    }
}
