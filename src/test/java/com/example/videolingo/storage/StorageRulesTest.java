package com.example.videolingo.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageRulesTest {

    private static StorageRules.Row row(long id, long file, long versions, long results) {
        return new StorageRules.Row(id, "V" + id, false, file, 1, versions, 1, results, 0, 0);
    }

    @Test
    void theBiggestVideosComeFirstAndTheTotalsAddUp() {
        List<StorageRules.Row> rows =
                List.of(row(1, 100, 0, 0), row(2, 50, 200, 10), row(3, 0, 0, 0), row(4, 100, 0, 0));
        assertEquals(
                List.of(2L, 1L, 4L),
                StorageRules.biggest(rows, 10).stream()
                        .map(StorageRules.Row::videoId)
                        .toList());
        assertEquals(1, StorageRules.biggest(rows, 1).size());
        StorageRules.Totals t = StorageRules.totals(rows);
        assertEquals(250, t.files());
        assertEquals(200, t.versions());
        assertEquals(460, t.all());
    }

    @Test
    void aCleanUpKeepsTheNewestAndOnlyRemovesOldOnes() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0);
        List<StorageRules.Version> versions = List.of(
                new StorageRules.Version(1, now.minusDays(90), 10),
                new StorageRules.Version(2, now.minusDays(60), 20),
                new StorageRules.Version(3, now.minusDays(5), 30),
                new StorageRules.Version(4, now.minusDays(1), 40),
                new StorageRules.Version(5, null, 50));
        // Keep the newest 2 (4 and 3); of the rest, only those older than 30 days (and the undated one).
        assertEquals(
                List.of(2L, 1L, 5L),
                StorageRules.toPrune(versions, 2, now.minusDays(30)).stream()
                        .map(StorageRules.Version::id)
                        .toList());
        assertEquals(List.of(), StorageRules.toPrune(versions, 5, now));
        assertNull(StorageRules.validatePrune(1, 30));
        assertNotNull(StorageRules.validatePrune(21, 30));
        assertNotNull(StorageRules.validatePrune(1, -1));
    }
}
