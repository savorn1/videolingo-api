package com.example.videolingo.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.videolingo.entity.SeriesCategory;
import com.example.videolingo.entity.SeriesStatus;
import com.example.videolingo.entity.StreamingSeries;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingSettingsDtoTest {

    @Test
    void settingsRoundTripThroughTheEntity() {
        StreamingSettingsDto in = StreamingSettingsDto.builder()
                .enabled(true)
                .category(SeriesCategory.ROMANCE)
                .status(SeriesStatus.COMPLETED)
                .vip(true)
                .freeEpisodes(5)
                .recapSeconds(12)
                .searchKeywords("  summer love sneha ")
                .region("ចិន")
                .releaseYear(2026)
                .genres(List.of("ស្នេហា", " ទីក្រុង "))
                .bannerUrl("https://cdn.example/banner.jpg")
                .verticalUrl(" ")
                .build();

        StreamingSeries entity = new StreamingSeries();
        in.applyTo(entity);
        StreamingSettingsDto out = StreamingSettingsDto.from(entity);

        assertEquals(SeriesCategory.ROMANCE, out.getCategory());
        assertEquals(SeriesStatus.COMPLETED, out.getStatus());
        assertEquals(5, out.getFreeEpisodes());
        assertEquals("summer love sneha", out.getSearchKeywords());
        assertEquals(List.of("ស្នេហា", "ទីក្រុង"), out.getGenres());
        assertNull(out.getVerticalUrl(), "blank strings are stored as null");
    }

    @Test
    void commasInsideAGenreDoNotSplitIt() {
        assertEquals(
                List.of("love city", "drama"),
                StreamingSettingsDto.splitGenres(
                        StreamingSettingsDto.joinGenres(Arrays.asList("love, city", null, "drama"))));
    }

    @Test
    void missingSettingsReadAsOff() {
        StreamingSettingsDto out = StreamingSettingsDto.from(null);
        assertFalse(out.isEnabled());
        assertEquals(SeriesStatus.ONGOING, out.getStatus());
        assertEquals(3, out.getFreeEpisodes());
        assertEquals(List.of(), out.getGenres());
    }
}
