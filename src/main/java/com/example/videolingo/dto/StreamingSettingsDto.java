package com.example.videolingo.dto;

import com.example.videolingo.entity.SeriesCategory;
import com.example.videolingo.entity.SeriesStatus;
import com.example.videolingo.entity.StreamingSeries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// A collection's settings as a series on the public streaming site (RuengFlix),
// sent and returned with the collection. On update, leaving it out keeps them.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamingSettingsDto {

    private boolean enabled;

    private SeriesCategory category;

    private SeriesStatus status;

    private boolean vip;

    @Min(0)
    @Max(100)
    private int freeEpisodes;

    @Min(0)
    @Max(300)
    private int recapSeconds;

    @Size(max = 200)
    private String searchKeywords;

    @Size(max = 40)
    private String region;

    @Min(1990)
    @Max(2100)
    private Integer releaseYear;

    @Size(max = 5)
    private List<@Size(max = 60) String> genres;

    @Size(max = 1000)
    private String bannerUrl;

    @Size(max = 1000)
    private String verticalUrl;

    // Place in RuengFlix's hero slider (1 first); null = not a hero pick.
    @Min(1)
    @Max(99)
    private Integer featuredRank;

    public static StreamingSettingsDto from(StreamingSeries s) {
        StreamingSeries v = s != null ? s : new StreamingSeries();
        return StreamingSettingsDto.builder()
                .enabled(v.isEnabled())
                .category(v.getCategory())
                .status(v.getStatus())
                .vip(v.isVip())
                .freeEpisodes(v.getFreeEpisodes())
                .recapSeconds(v.getRecapSeconds())
                .searchKeywords(v.getSearchKeywords())
                .region(v.getRegion())
                .releaseYear(v.getReleaseYear())
                .genres(splitGenres(v.getGenres()))
                .bannerUrl(v.getBannerUrl())
                .verticalUrl(v.getVerticalUrl())
                .featuredRank(v.getFeaturedRank())
                .build();
    }

    /** Copies these settings onto the entity (blank strings stored as null). */
    public void applyTo(StreamingSeries s) {
        s.setEnabled(enabled);
        s.setCategory(category);
        s.setStatus(status != null ? status : SeriesStatus.ONGOING);
        s.setVip(vip);
        s.setFreeEpisodes(freeEpisodes);
        s.setRecapSeconds(recapSeconds);
        s.setSearchKeywords(blankToNull(searchKeywords));
        s.setRegion(blankToNull(region));
        s.setReleaseYear(releaseYear);
        s.setGenres(joinGenres(genres));
        s.setBannerUrl(blankToNull(bannerUrl));
        s.setVerticalUrl(blankToNull(verticalUrl));
        s.setFeaturedRank(featuredRank);
    }

    public static List<String> splitGenres(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::strip)
                .filter(g -> !g.isEmpty())
                .toList();
    }

    static String joinGenres(List<String> genres) {
        if (genres == null) {
            return null;
        }
        String joined = String.join(
                ", ",
                genres.stream()
                        .filter(g -> g != null && !g.isBlank())
                        // A comma inside a genre would split it in two when read back.
                        .map(g -> g.replace(",", " ").strip().replaceAll("\\s+", " "))
                        .toList());
        return joined.isEmpty() ? null : joined;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
