package com.example.videolingo.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.videolingo.exception.AppException;
import com.example.videolingo.streaming.StreamingHomeRowsService.HomeRow;
import com.example.videolingo.streaming.StreamingHomeRowsService.HomeRowRequest;
import org.junit.jupiter.api.Test;

class StreamingHomeRowsServiceTest {

    @Test
    void aNewRowNeedsAHeadingAndASource() {
        HomeRowRequest r =
                StreamingHomeRowsService.valid(new HomeRowRequest(" អាប់ដេតថ្មី ", "latest", "x", null, null), null);
        assertEquals("អាប់ដេតថ្មី", r.title());
        assertEquals("LATEST", r.source());
        assertNull(r.value(), "newest and popular rows have no value");
        assertEquals(12, r.maxItems());
        assertTrue(r.active());
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(new HomeRowRequest("", "LATEST", null, null, null), null));
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(new HomeRowRequest("Row", "SHORTS", null, null, null), null));
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(
                        new HomeRowRequest("x".repeat(81), "LATEST", null, null, null), null));
    }

    @Test
    void aCategoryRowNamesAKnownCategoryAndAGenreRowAGenre() {
        assertEquals(
                "ROMANCE",
                StreamingHomeRowsService.valid(new HomeRowRequest("ស្នេហា", "CATEGORY", "romance", 8, true), null)
                        .value());
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(
                        new HomeRowRequest("Row", "CATEGORY", "HORROR", null, null), null));
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(new HomeRowRequest("Row", "GENRE", " ", null, null), null));
        assertEquals(
                "យុទ្ធភពបុរាណ",
                StreamingHomeRowsService.valid(new HomeRowRequest("Row", "GENRE", "យុទ្ធភពបុរាណ", null, null), null)
                        .value());
    }

    @Test
    void showsFrom4To30Series() {
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(new HomeRowRequest("Row", "LATEST", null, 3, null), null));
        assertThrows(
                AppException.class,
                () -> StreamingHomeRowsService.valid(new HomeRowRequest("Row", "LATEST", null, 31, null), null));
    }

    @Test
    void anEditKeepsWhatItLeavesOut() {
        HomeRow current = new HomeRow(1, "ក្បាច់គុន", "CATEGORY", "CULTIVATION", 10, 1, true, "seed", null, null);
        HomeRowRequest r = StreamingHomeRowsService.valid(new HomeRowRequest(null, null, null, null, false), current);
        assertEquals("ក្បាច់គុន", r.title());
        assertEquals("CULTIVATION", r.value());
        assertEquals(10, r.maxItems());
        assertEquals(false, r.active());
    }
}
