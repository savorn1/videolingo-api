package com.example.videolingo.streaming;

import com.example.videolingo.exception.AppException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// RuengFlix's home page rows (V25): each a heading and where its series come from, in order. movie-api reads the
// active ones; RuengFlix fills each from its catalog.
@Service
@RequiredArgsConstructor
public class StreamingHomeRowsService {

    private final NamedParameterJdbcTemplate jdbc;

    public static final Set<String> SOURCES = Set.of("LATEST", "POPULAR", "CATEGORY", "GENRE");
    public static final Set<String> CATEGORIES = Set.of("CULTIVATION", "ROMANCE", "FANTASY");
    public static final int MAX_ROWS = 20;

    public record HomeRow(
            long id,
            String title,
            String source,
            String value,
            int maxItems,
            int position,
            boolean active,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    public record HomeRowRequest(String title, String source, String value, Integer maxItems, Boolean active) {}

    private static final String SELECT = """
            select id, title, source, value, max_items, position, active, created_by, created_at, updated_at
            from streaming_home_rows
            """;

    public List<HomeRow> rows() {
        return jdbc.query(SELECT + " order by position, id", Map.of(), this::mapRow);
    }

    @Transactional
    public HomeRow create(HomeRowRequest req, String actor) {
        HomeRowRequest r = valid(req, null);
        Integer count = jdbc.queryForObject("select count(*) from streaming_home_rows", Map.of(), Integer.class);
        if (count != null && count >= MAX_ROWS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "The home page has room for " + MAX_ROWS + " rows");
        }
        Long id = jdbc.queryForObject("""
                insert into streaming_home_rows (title, source, value, max_items, active, position, created_by)
                values (:title, :source, :value, :max, :active,
                        (select coalesce(max(position) + 1, 0) from streaming_home_rows), :by)
                returning id
                """, params(r).addValue("by", actor), Long.class);
        return row(id);
    }

    public HomeRow update(long id, HomeRowRequest req) {
        HomeRowRequest r = valid(req, row(id));
        jdbc.update("""
                update streaming_home_rows
                set title = :title, source = :source, value = :value, max_items = :max, active = :active,
                    updated_at = now()
                where id = :id
                """, params(r).addValue("id", id));
        return row(id);
    }

    public void delete(long id) {
        if (jdbc.update("delete from streaming_home_rows where id = :id", Map.of("id", id)) == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Home row not found");
        }
    }

    /** Puts the rows in the order given; it has to name every row once. */
    @Transactional
    public List<HomeRow> reorder(List<Long> ids) {
        List<HomeRow> current = rows();
        Set<Long> known = new HashSet<>(current.stream().map(HomeRow::id).toList());
        if (ids == null || ids.size() != known.size() || !known.equals(new HashSet<>(ids))) {
            throw new AppException(
                    HttpStatus.BAD_REQUEST, "The order has to list every row once — reload and try again");
        }
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update(
                    "update streaming_home_rows set position = :p, updated_at = now() where id = :id",
                    Map.of("p", i, "id", ids.get(i)));
        }
        return rows();
    }

    /** Fills gaps from the current row when editing, then checks every field. */
    static HomeRowRequest valid(HomeRowRequest req, HomeRow current) {
        if (req == null) throw new AppException(HttpStatus.BAD_REQUEST, "Nothing to save");
        String title = req.title() != null ? req.title().strip() : current == null ? null : current.title();
        String source =
                req.source() != null ? req.source().strip().toUpperCase() : current == null ? null : current.source();
        String value = req.value() != null ? req.value().strip() : current == null ? null : current.value();
        int max = req.maxItems() != null ? req.maxItems() : current == null ? 12 : current.maxItems();
        boolean active = req.active() != null ? req.active() : current == null || current.active();
        if (title == null || title.isEmpty() || title.length() > 80) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Give the row a heading (up to 80 characters)");
        }
        if (source == null || !SOURCES.contains(source)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Choose where the row's series come from");
        }
        if (source.equals("CATEGORY")) {
            value = value == null ? null : value.toUpperCase();
            if (value == null || !CATEGORIES.contains(value)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Choose the category the row shows");
            }
        } else if (source.equals("GENRE")) {
            if (value == null || value.isEmpty() || value.length() > 80) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Type the genre the row shows (up to 80 characters)");
            }
        } else {
            value = null;
        }
        if (max < 4 || max > 30) {
            throw new AppException(HttpStatus.BAD_REQUEST, "A row shows from 4 to 30 series");
        }
        return new HomeRowRequest(title, source, value, max, active);
    }

    private HomeRow row(long id) {
        return jdbc.query(SELECT + " where id = :id", Map.of("id", id), this::mapRow).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Home row not found"));
    }

    private static MapSqlParameterSource params(HomeRowRequest r) {
        return new MapSqlParameterSource()
                .addValue("title", r.title())
                .addValue("source", r.source())
                .addValue("value", r.value())
                .addValue("max", r.maxItems())
                .addValue("active", r.active());
    }

    private HomeRow mapRow(ResultSet rs, int i) throws SQLException {
        return new HomeRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getInt(5),
                rs.getInt(6),
                rs.getBoolean(7),
                rs.getString(8),
                time(rs, 9),
                time(rs, 10));
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
