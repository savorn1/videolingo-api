package com.example.videolingo.storage;

import com.example.videolingo.entity.VideoVersion;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.VideoClipRepository;
import com.example.videolingo.repository.VideoDubRepository;
import com.example.videolingo.repository.VideoExportRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.repository.VideoVersionRepository;
import com.example.videolingo.service.VideoVersionService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The storage report (what each video's files take) and the clean-up of old versions. The sizes are the ones the
// rows keep (saved when each file was made), so the report reads the database, not the storage.
@Service
@RequiredArgsConstructor
public class StorageService {

    /** How many of the biggest videos the report lists. */
    public static final int TOP = 50;

    private final VideoRepository videoRepository;
    private final VideoVersionRepository versionRepository;
    private final VideoClipRepository clipRepository;
    private final VideoDubRepository dubRepository;
    private final VideoExportRepository exportRepository;
    private final VideoVersionService versionService;

    public record Report(StorageRules.Totals totals, long totalBytes, int videoCount, List<StorageRules.Row> biggest) {}

    public record PruneResult(int removed, long freedBytes) {}

    @Transactional(readOnly = true)
    public Report report() {
        Map<Long, long[]> versions = sums(versionRepository.sizeByVideo());
        Map<Long, long[]> results = sums(clipRepository.sizeByVideo());
        Map<Long, long[]> dubs = sums(dubRepository.sizeByVideo());
        Map<Long, long[]> exports = sums(exportRepository.sizeByVideo());
        long[] none = {0, 0};
        List<StorageRules.Row> rows = videoRepository.fileSizes().stream()
                .map(v -> {
                    long id = ((Number) v[0]).longValue();
                    long[] ver = versions.getOrDefault(id, none);
                    long[] res = results.getOrDefault(id, none);
                    return new StorageRules.Row(
                            id,
                            (String) v[1],
                            v[3] != null,
                            ((Number) v[2]).longValue(),
                            (int) ver[0],
                            ver[1],
                            (int) res[0],
                            res[1],
                            dubs.getOrDefault(id, none)[1],
                            exports.getOrDefault(id, none)[1]);
                })
                .toList();
        StorageRules.Totals totals = StorageRules.totals(rows);
        return new Report(totals, totals.all(), rows.size(), StorageRules.biggest(rows, TOP));
    }

    /** Removes the old versions of every video (StorageRules.toPrune), files included. Can't be undone. */
    @Transactional
    public PruneResult pruneVersions(int keepLatest, int olderThanDays) {
        String problem = StorageRules.validatePrune(keepLatest, olderThanDays);
        if (problem != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, problem);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(olderThanDays);
        Map<Long, List<VideoVersion>> byVideo =
                versionRepository.findAll().stream().collect(Collectors.groupingBy(VideoVersion::getVideoId));
        int removed = 0;
        long freed = 0;
        for (Map.Entry<Long, List<VideoVersion>> e : byVideo.entrySet()) {
            List<StorageRules.Version> kept = e.getValue().stream()
                    .map(v -> new StorageRules.Version(
                            v.getId(), v.getCreatedAt(), v.getFileSize() == null ? 0 : v.getFileSize()))
                    .toList();
            for (StorageRules.Version v : StorageRules.toPrune(kept, keepLatest, cutoff)) {
                versionService.remove(e.getKey(), v.id());
                removed++;
                freed += v.bytes();
            }
        }
        return new PruneResult(removed, freed);
    }

    private static Map<Long, long[]> sums(List<Object[]> rows) {
        Map<Long, long[]> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(((Number) r[0]).longValue(), new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        return out;
    }
}
