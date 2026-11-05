package com.example.videolingo.streaming;

import com.example.videolingo.exception.AppException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin portal's view of RuengFlix money and community (V21 tables, written by movie-api): KHQR
 * orders (confirm or cancel through movie-api, which delivers what was bought), comments (hide or show),
 * and a dashboard of revenue, coins, VIP and viewer retention.
 */
@Service
@RequiredArgsConstructor
public class StreamingCommerceAdminService {

    private final NamedParameterJdbcTemplate jdbc;
    private final RuengFlixClient ruengFlix;

    // ── orders ────────────────────────────────────────────────────────────

    public record OrderRow(
            long id,
            Long viewerId,
            String viewerName,
            String kind,
            BigDecimal amountUsd,
            Integer coins,
            Long seriesId,
            String seriesTitle,
            Integer months,
            String status,
            String billNumber,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            LocalDateTime paidAt,
            String paidBy,
            String bakongHash,
            String note,
            // First top-up bonus added on top of a coin pack (V22)
            Integer bonusCoins,
            // Refunds (V23)
            LocalDateTime refundedAt,
            String refundedBy,
            String refundReason,
            // Extra coins from a running promotion (V24)
            Integer promoCoins,
            // Bought as a gift: the code, and who redeemed it (V24)
            boolean gift,
            String giftCode,
            String giftRedeemedByName,
            LocalDateTime giftRedeemedAt) {}

    public record OrderPage(List<OrderRow> items, long total, int page, int size) {}

    private static final String ORDER_SELECT = """
            select o.id, o.viewer_id, v.display_name, o.kind, o.amount_usd, o.coins, o.collection_id, c.title,
                   o.months, o.status, o.bill_number, o.created_at, o.expires_at, o.paid_at, o.paid_by,
                   o.bakong_hash, o.note, o.bonus_coins, o.refunded_at, o.refunded_by, o.refund_reason,
                   o.promo_coins, o.gift, g.code, rv.display_name, g.redeemed_at
            from streaming_orders o
            left join streaming_viewers v on v.id = o.viewer_id
            left join collections c on c.id = o.collection_id
            left join streaming_gifts g on g.order_id = o.id
            left join streaming_viewers rv on rv.id = g.redeemed_by
            """;

    /** Newest first. {@code status}: all, PENDING, PAID, EXPIRED or CANCELLED. {@code q}: bill number or viewer name. */
    public OrderPage orders(String status, String q, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("limit", s).addValue("offset", (p - 1) * s);
        StringBuilder where = new StringBuilder(" where true");
        if (status != null
                && List.of("PENDING", "PAID", "EXPIRED", "CANCELLED", "REFUNDED")
                        .contains(status)) {
            where.append(" and o.status = :status");
            params.addValue("status", status);
        }
        String query = q == null ? "" : q.strip();
        if (!query.isEmpty()) {
            where.append(" and (upper(o.bill_number) like :q or upper(v.display_name) like :q)");
            params.addValue("q", "%" + query.toUpperCase().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.queryForObject(
                "select count(*) from streaming_orders o left join streaming_viewers v on v.id = o.viewer_id" + where,
                params,
                Long.class);
        List<OrderRow> items = jdbc.query(
                ORDER_SELECT + where + " order by o.created_at desc, o.id desc limit :limit offset :offset",
                params,
                this::orderRow);
        return new OrderPage(items, total == null ? 0 : total, p, s);
    }

    /** Confirm a payment the admin checked in the bank app; movie-api delivers what was bought (once). */
    public OrderRow markPaid(long id, String actor) {
        ruengFlix.orderAction(id, "paid", actorName(actor));
        return order(id);
    }

    public OrderRow cancel(long id, String actor) {
        ruengFlix.orderAction(id, "cancel", actorName(actor));
        return order(id);
    }

    /** Refund a paid order: movie-api takes back what it delivered; the money goes back outside RuengFlix. */
    public OrderRow refund(long id, String actor, String reason) {
        ruengFlix.refundOrder(id, actorName(actor), requireReason(reason));
        return order(id);
    }

    private OrderRow order(long id) {
        return jdbc.query(ORDER_SELECT + " where o.id = :id", Map.of("id", id), this::orderRow).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private OrderRow orderRow(ResultSet rs, int i) throws SQLException {
        return new OrderRow(
                rs.getLong(1),
                rs.getObject(2, Long.class),
                rs.getString(3),
                rs.getString(4),
                rs.getBigDecimal(5),
                rs.getObject(6, Integer.class),
                rs.getObject(7, Long.class),
                rs.getString(8),
                rs.getObject(9, Integer.class),
                rs.getString(10),
                rs.getString(11),
                time(rs, 12),
                time(rs, 13),
                time(rs, 14),
                rs.getString(15),
                rs.getString(16),
                rs.getString(17),
                rs.getObject(18, Integer.class),
                time(rs, 19),
                rs.getString(20),
                rs.getString(21),
                rs.getObject(22, Integer.class),
                rs.getBoolean(23),
                rs.getString(24),
                rs.getString(25),
                time(rs, 26));
    }

    // ── comments ──────────────────────────────────────────────────────────

    public record CommentRow(
            long id,
            long seriesId,
            String seriesTitle,
            int episode,
            long viewerId,
            String viewerName,
            String body,
            int reportCount,
            LocalDateTime createdAt,
            LocalDateTime hiddenAt,
            String hiddenBy,
            int likeCount,
            LocalDateTime pinnedAt,
            String pinnedBy,
            // The latest official reply under it, if any (V23)
            Long replyId,
            String replyBody,
            String replyBy) {}

    public record CommentPage(List<CommentRow> items, long total, int page, int size) {}

    private static final String COMMENT_SELECT = """
            select m.id, m.collection_id, c.title, m.episode, m.viewer_id, v.display_name, m.body, m.report_count,
                   m.created_at, m.hidden_at, m.hidden_by, m.like_count, m.pinned_at, m.pinned_by,
                   r.id, r.body, r.author_admin
            from streaming_comments m
            join collections c on c.id = m.collection_id
            join streaming_viewers v on v.id = m.viewer_id
            left join lateral (
                select x.id, x.body, x.author_admin from streaming_comments x
                where x.parent_id = m.id and x.author_admin is not null
                order by x.created_at desc, x.id desc limit 1) r on true
            """;

    /** {@code filter}: reported (most reported first), hidden, pinned, or all (newest first). */
    public CommentPage comments(String filter, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String where;
        String order;
        switch (filter == null ? "all" : filter) {
            case "reported" -> {
                where = " where m.parent_id is null and m.report_count > 0";
                order = " order by m.hidden_at is null desc, m.report_count desc, m.created_at desc";
            }
            case "hidden" -> {
                where = " where m.parent_id is null and m.hidden_at is not null";
                order = " order by m.hidden_at desc";
            }
            case "pinned" -> {
                where = " where m.parent_id is null and m.pinned_at is not null";
                order = " order by m.pinned_at desc";
            }
            default -> {
                where = " where m.parent_id is null";
                order = " order by m.created_at desc, m.id desc";
            }
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("limit", s).addValue("offset", (p - 1) * s);
        Long total = jdbc.queryForObject("select count(*) from streaming_comments m" + where, params, Long.class);
        List<CommentRow> items = jdbc.query(
                COMMENT_SELECT + where + order + ", m.id desc limit :limit offset :offset", params, this::commentRow);
        return new CommentPage(items, total == null ? 0 : total, p, s);
    }

    /** Hide a comment from RuengFlix, or show it again (which also clears its reports). */
    @Transactional
    public CommentRow setHidden(long id, boolean hidden, String actor) {
        int changed = hidden
                ? jdbc.update(
                        "update streaming_comments set hidden_at = coalesce(hidden_at, localtimestamp),"
                                + " hidden_by = coalesce(hidden_by, :actor), pinned_at = null, pinned_by = null"
                                + " where id = :id",
                        Map.of("id", id, "actor", actorName(actor)))
                : jdbc.update(
                        "update streaming_comments set hidden_at = null, hidden_by = null, report_count = 0"
                                + " where id = :id",
                        Map.of("id", id));
        if (changed == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        if (!hidden) {
            jdbc.update("delete from streaming_comment_reports where comment_id = :id", Map.of("id", id));
        }
        return jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", id), this::commentRow)
                .get(0);
    }

    /**
     * Pin a comment to the top of its episode on RuengFlix, or unpin it. One pinned comment per episode:
     * pinning replaces the one pinned before. A hidden comment can't be pinned.
     */
    @Transactional
    public CommentRow setPinned(long id, boolean pinned, String actor) {
        List<CommentRow> found = jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", id), this::commentRow);
        if (found.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        CommentRow c = found.get(0);
        if (pinned) {
            if (c.hiddenAt() != null) {
                throw new AppException(HttpStatus.CONFLICT, "Show the comment before pinning it");
            }
            jdbc.update(
                    "update streaming_comments set pinned_at = null, pinned_by = null"
                            + " where collection_id = :series and episode = :ep and id <> :id and pinned_at is not null",
                    Map.of("series", c.seriesId(), "ep", c.episode(), "id", id));
            jdbc.update(
                    "update streaming_comments set pinned_at = coalesce(pinned_at, localtimestamp),"
                            + " pinned_by = coalesce(pinned_by, :actor) where id = :id",
                    Map.of("id", id, "actor", actorName(actor)));
        } else {
            jdbc.update(
                    "update streaming_comments set pinned_at = null, pinned_by = null where id = :id",
                    Map.of("id", id));
        }
        return jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", id), this::commentRow)
                .get(0);
    }

    /**
     * Answer a viewer's comment as RuengFlix: shown under it with an official badge. One official reply
     * per comment; writing again replaces it.
     */
    @Transactional
    public CommentRow reply(long commentId, String body, String actor) {
        String text = body == null ? "" : body.strip().replaceAll("\\n{3,}", "\n\n");
        if (text.isEmpty() || text.length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Write a reply of up to 500 characters");
        }
        List<CommentRow> found =
                jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", commentId), this::commentRow);
        if (found.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        CommentRow c = found.get(0);
        jdbc.update(
                "delete from streaming_comments where parent_id = :id and author_admin is not null",
                Map.of("id", commentId));
        jdbc.update(
                "insert into streaming_comments (collection_id, episode, viewer_id, body, created_at, parent_id,"
                        + " author_admin) values (:series, :ep, null, :body, localtimestamp, :id, :actor)",
                Map.of(
                        "series", c.seriesId(),
                        "ep", c.episode(),
                        "body", text,
                        "id", commentId,
                        "actor", actorName(actor)));
        return jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", commentId), this::commentRow)
                .get(0);
    }

    /** Take back the official reply under a comment. */
    public CommentRow deleteReply(long commentId) {
        jdbc.update(
                "delete from streaming_comments where parent_id = :id and author_admin is not null",
                Map.of("id", commentId));
        return jdbc.query(COMMENT_SELECT + " where m.id = :id", Map.of("id", commentId), this::commentRow).stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Comment not found"));
    }

    // ── viewer support ────────────────────────────────────────────────────

    public record WalletInfo(int coins, LocalDateTime vipUntil, boolean vipActive) {}

    public record UnlockRow(long seriesId, String seriesTitle, int episode, String source, LocalDateTime createdAt) {}

    public record LedgerRow(long id, int delta, String reason, String ref, LocalDateTime createdAt) {}

    public record InviteInfo(String code, String invitedByName, Long invitedById, long joined, long rewarded) {}

    public record ViewerBasics(
            long id,
            String displayName,
            String phone,
            String telegramUsername,
            boolean telegramLinked,
            String googleEmail,
            boolean disabled,
            LocalDateTime createdAt,
            LocalDateTime lastLoginAt) {}

    public record ViewerAccount(
            ViewerBasics viewer,
            WalletInfo wallet,
            List<OrderRow> orders,
            List<UnlockRow> unlocks,
            List<LedgerRow> ledger,
            InviteInfo invites) {}

    /** Everything about a viewer's money for a support case: wallet, orders, unlocks, coin history, invites. */
    public ViewerAccount account(long viewerId) {
        Map<String, Object> id = Map.of("id", viewerId);
        ViewerBasics viewer = jdbc
                .query(
                        "select id, display_name, phone, telegram_username, telegram_id is not null, google_email,"
                                + " disabled, created_at, last_login_at from streaming_viewers where id = :id",
                        id,
                        (rs, i) -> new ViewerBasics(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getBoolean(5),
                                rs.getString(6),
                                rs.getBoolean(7),
                                time(rs, 8),
                                time(rs, 9)))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Viewer not found"));
        WalletInfo wallet = jdbc
                .query(
                        "select coins, vip_until, vip_until > localtimestamp from streaming_wallets where viewer_id = :id",
                        id,
                        (rs, i) -> new WalletInfo(rs.getInt(1), time(rs, 2), rs.getBoolean(3)))
                .stream()
                .findFirst()
                .orElse(new WalletInfo(0, null, false));
        List<OrderRow> orders = jdbc.query(
                ORDER_SELECT + " where o.viewer_id = :id order by o.created_at desc, o.id desc limit 50",
                id,
                this::orderRow);
        List<UnlockRow> unlocks = jdbc.query(
                "select e.collection_id, c.title, e.episode, e.source, e.created_at from streaming_entitlements e"
                        + " left join collections c on c.id = e.collection_id where e.viewer_id = :id"
                        + " order by e.created_at desc limit 100",
                id,
                (rs, i) -> new UnlockRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), time(rs, 5)));
        List<LedgerRow> ledger = jdbc.query(
                "select id, delta, reason, ref, created_at from streaming_coin_ledger where viewer_id = :id"
                        + " order by created_at desc, id desc limit 50",
                id,
                (rs, i) -> new LedgerRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), time(rs, 5)));
        InviteInfo invites = jdbc.query(
                        "select v.invite_code, i.display_name, i.id,"
                                + " (select count(*) from streaming_referrals where inviter_id = v.id),"
                                + " (select count(*) from streaming_referrals where inviter_id = v.id"
                                + "   and rewarded_at is not null)"
                                + " from streaming_viewers v"
                                + " left join streaming_referrals r on r.invitee_id = v.id"
                                + " left join streaming_viewers i on i.id = r.inviter_id where v.id = :id",
                        id,
                        (rs, i) -> new InviteInfo(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getObject(3, Long.class),
                                rs.getLong(4),
                                rs.getLong(5)))
                .get(0);
        return new ViewerAccount(viewer, wallet, orders, unlocks, ledger, invites);
    }

    /** Give coins (or take some back with a negative number) for a support case; movie-api keeps the ledger. */
    public ViewerAccount grantCoins(long viewerId, int coins, String reason, String actor) {
        if (coins == 0 || Math.abs(coins) > 10_000) {
            throw new AppException(HttpStatus.BAD_REQUEST, "From -10,000 to 10,000 coins, not 0");
        }
        ruengFlix.grantCoins(viewerId, coins, actorName(actor), requireReason(reason));
        return account(viewerId);
    }

    private static String requireReason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.length() < 3 || r.length() > 200) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Give a reason (3 to 200 characters)");
        }
        return r;
    }

    // ── menu badges ───────────────────────────────────────────────────────

    /** What needs an admin: orders waiting for confirmation, and reported comments still showing. */
    public record Badges(long pendingOrders, long reportedComments) {}

    public Badges badges() {
        Long orders = jdbc.queryForObject(
                "select count(*) from streaming_orders where status = 'PENDING'", Map.of(), Long.class);
        Long comments = jdbc.queryForObject(
                "select count(*) from streaming_comments where report_count > 0 and hidden_at is null"
                        + " and parent_id is null",
                Map.of(),
                Long.class);
        return new Badges(orders == null ? 0 : orders, comments == null ? 0 : comments);
    }

    private CommentRow commentRow(ResultSet rs, int i) throws SQLException {
        return new CommentRow(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getInt(4),
                rs.getLong(5),
                rs.getString(6),
                rs.getString(7),
                rs.getInt(8),
                time(rs, 9),
                time(rs, 10),
                rs.getString(11),
                rs.getInt(12),
                time(rs, 13),
                rs.getString(14),
                rs.getObject(15, Long.class),
                rs.getString(16),
                rs.getString(17));
    }

    // ── dashboard ─────────────────────────────────────────────────────────

    public record DayAmount(LocalDate day, BigDecimal amountUsd, long orders) {}

    public record KindAmount(String kind, BigDecimal amountUsd, long orders) {}

    public record SeriesAmount(long seriesId, String title, BigDecimal amountUsd, long orders) {}

    /** Of the viewers who joined in the window, the share who came back that many days later. */
    public record Retention(int day, long cohort, long returned, Double rate) {}

    public record Dashboard(
            BigDecimal revenue7Days,
            BigDecimal revenue30Days,
            long pendingOrders,
            List<DayAmount> revenueByDay,
            List<KindAmount> revenueByKind,
            List<SeriesAmount> topSeries,
            long coinsHeld,
            long coinsSpent30Days,
            long vipActive,
            long viewers,
            long newViewers7Days,
            long activeToday,
            long active7Days,
            List<Retention> retention) {}

    public Dashboard dashboard() {
        Map<String, Object> none = Map.of();
        List<DayAmount> byDay = jdbc.query(
                """
                select cast(paid_at as date) d, coalesce(sum(amount_usd), 0) amt, count(*) n from streaming_orders
                where status = 'PAID' and paid_at >= localtimestamp - interval '30 days'
                group by 1 order by 1
                """, none, (rs, i) -> new DayAmount(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getLong(3)));
        List<KindAmount> byKind =
                jdbc.query("""
                select kind, coalesce(sum(amount_usd), 0), count(*) from streaming_orders
                where status = 'PAID' and paid_at >= localtimestamp - interval '30 days'
                group by kind order by 2 desc
                """, none, (rs, i) -> new KindAmount(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)));
        List<SeriesAmount> top = jdbc.query(
                """
                select o.collection_id, c.title, sum(o.amount_usd), count(*) from streaming_orders o
                join collections c on c.id = o.collection_id
                where o.status = 'PAID' and o.kind = 'SERIES' and o.paid_at >= localtimestamp - interval '30 days'
                group by o.collection_id, c.title order by 3 desc limit 5
                """,
                none,
                (rs, i) -> new SeriesAmount(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4)));
        Map<String, Object> n = jdbc.queryForMap("""
                select
                  (select coalesce(sum(amount_usd), 0) from streaming_orders
                     where status = 'PAID' and paid_at >= localtimestamp - interval '7 days') rev7,
                  (select coalesce(sum(amount_usd), 0) from streaming_orders
                     where status = 'PAID' and paid_at >= localtimestamp - interval '30 days') rev30,
                  (select count(*) from streaming_orders where status = 'PENDING') pending,
                  (select coalesce(sum(coins), 0) from streaming_wallets) coins_held,
                  (select coalesce(-sum(delta), 0) from streaming_coin_ledger
                     where reason = 'UNLOCK' and created_at >= localtimestamp - interval '30 days') coins_spent,
                  (select count(*) from streaming_wallets where vip_until > localtimestamp) vip,
                  (select count(*) from streaming_viewers) viewers,
                  (select count(*) from streaming_viewers where created_at >= localtimestamp - interval '7 days') new7,
                  (select count(*) from streaming_viewer_days where day = current_date) today,
                  (select count(distinct viewer_id) from streaming_viewer_days
                     where day > current_date - 7) active7
                """, none);
        List<Retention> retention = new ArrayList<>();
        for (int day : new int[] {1, 7, 30}) {
            retention.add(retention(day));
        }
        return new Dashboard(
                (BigDecimal) n.get("rev7"),
                (BigDecimal) n.get("rev30"),
                ((Number) n.get("pending")).longValue(),
                byDay,
                byKind,
                top,
                ((Number) n.get("coins_held")).longValue(),
                ((Number) n.get("coins_spent")).longValue(),
                ((Number) n.get("vip")).longValue(),
                ((Number) n.get("viewers")).longValue(),
                ((Number) n.get("new7")).longValue(),
                ((Number) n.get("today")).longValue(),
                ((Number) n.get("active7")).longValue(),
                retention);
    }

    /**
     * Day-N retention over the last 60 days of sign-ups old enough to have had a day N: the share who used
     * RuengFlix (signed in) exactly N days after joining.
     */
    private Retention retention(int day) {
        Map<String, Object> r = jdbc.queryForMap("""
                with cohort as (
                  select id, cast(created_at as date) joined from streaming_viewers
                  where cast(created_at as date) between current_date - 60 - :day and current_date - :day
                )
                select count(*) cohort,
                       count(*) filter (where exists (
                         select 1 from streaming_viewer_days d where d.viewer_id = cohort.id and d.day = cohort.joined + :day
                       )) returned
                from cohort
                """, Map.of("day", day));
        long cohort = ((Number) r.get("cohort")).longValue();
        long returned = ((Number) r.get("returned")).longValue();
        return new Retention(day, cohort, returned, retentionRate(cohort, returned));
    }

    /** Null when nobody is in the cohort yet (no rate to show). */
    static Double retentionRate(long cohort, long returned) {
        return cohort == 0 ? null : (double) returned / cohort;
    }

    private static String actorName(String actor) {
        String a = actor == null || actor.isBlank() ? "admin" : actor.strip();
        return a.length() > 100 ? a.substring(0, 100) : a;
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
