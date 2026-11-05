-- RuengFlix payments, coins, comments, browser push and viewer activity. movie-api writes these; the
-- admin portal reads them (orders, comments, revenue and retention) and can mark orders paid or hide
-- comments. Money is in US dollars. Deleting a viewer deletes their data except orders, which are
-- kept for the accounts with the viewer cleared.

-- Coins and VIP time per viewer. `coins` always equals the sum of their ledger.
CREATE TABLE IF NOT EXISTS public.streaming_wallets (
    viewer_id bigint PRIMARY KEY REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    coins integer NOT NULL DEFAULT 0 CHECK (coins >= 0),
    vip_until timestamp,
    updated_at timestamp NOT NULL DEFAULT now()
);

-- Every coin change: PURCHASE, CHECK_IN, UNLOCK, ADMIN. `ref` names what it was for (order id, "series:4:ep:7").
CREATE TABLE IF NOT EXISTS public.streaming_coin_ledger (
    id bigserial PRIMARY KEY,
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    delta integer NOT NULL,
    reason varchar(20) NOT NULL,
    ref varchar(100),
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_streaming_coin_ledger_viewer ON public.streaming_coin_ledger (viewer_id, created_at DESC);

-- A KHQR payment. kind COINS (coins), SERIES (collection_id) or VIP (months).
-- status PENDING → PAID (by Bakong's confirmation or an admin), EXPIRED or CANCELLED.
CREATE TABLE IF NOT EXISTS public.streaming_orders (
    id bigserial PRIMARY KEY,
    viewer_id bigint REFERENCES public.streaming_viewers (id) ON DELETE SET NULL,
    kind varchar(10) NOT NULL,
    amount_usd numeric(10, 2) NOT NULL CHECK (amount_usd > 0),
    coins integer,
    collection_id bigint REFERENCES public.collections (id) ON DELETE SET NULL,
    months integer,
    status varchar(10) NOT NULL DEFAULT 'PENDING',
    -- The bill number in the KHQR, and the md5 Bakong looks the payment up by
    bill_number varchar(25) NOT NULL UNIQUE,
    khqr text NOT NULL,
    khqr_md5 varchar(32) NOT NULL,
    -- When the QR stops being payable
    expires_at timestamp NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    paid_at timestamp,
    -- "bakong" or the admin who confirmed it
    paid_by varchar(100),
    -- Bakong's transaction hash, when it confirmed the payment
    bakong_hash varchar(100),
    note varchar(300)
);
CREATE INDEX IF NOT EXISTS idx_streaming_orders_viewer ON public.streaming_orders (viewer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_streaming_orders_status ON public.streaming_orders (status, created_at DESC);

-- What a viewer may watch beyond the free episodes: a whole series (episode 0) or one episode.
CREATE TABLE IF NOT EXISTS public.streaming_entitlements (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL DEFAULT 0,
    source varchar(10) NOT NULL,
    order_id bigint REFERENCES public.streaming_orders (id) ON DELETE SET NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_id, collection_id, episode)
);

-- One row per viewer per day they checked in; `coins` is what that day paid.
CREATE TABLE IF NOT EXISTS public.streaming_check_ins (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    day date NOT NULL,
    coins integer NOT NULL,
    streak integer NOT NULL,
    PRIMARY KEY (viewer_id, day)
);

-- Comments under an episode. Hidden ones stay for the record but aren't shown.
CREATE TABLE IF NOT EXISTS public.streaming_comments (
    id bigserial PRIMARY KEY,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    body varchar(500) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    report_count integer NOT NULL DEFAULT 0,
    hidden_at timestamp,
    hidden_by varchar(100)
);
CREATE INDEX IF NOT EXISTS idx_streaming_comments_episode ON public.streaming_comments (collection_id, episode, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_streaming_comments_reported ON public.streaming_comments (report_count DESC, created_at DESC) WHERE report_count > 0;

CREATE TABLE IF NOT EXISTS public.streaming_comment_reports (
    comment_id bigint NOT NULL REFERENCES public.streaming_comments (id) ON DELETE CASCADE,
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, viewer_id)
);

-- Browser push subscriptions (Web Push), with or without an account, and the series each follows.
CREATE TABLE IF NOT EXISTS public.streaming_push_subscriptions (
    id bigserial PRIMARY KEY,
    endpoint varchar(1000) NOT NULL UNIQUE,
    p256dh varchar(200) NOT NULL,
    auth varchar(100) NOT NULL,
    viewer_id bigint REFERENCES public.streaming_viewers (id) ON DELETE SET NULL,
    created_at timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.streaming_push_follows (
    subscription_id bigint NOT NULL REFERENCES public.streaming_push_subscriptions (id) ON DELETE CASCADE,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    PRIMARY KEY (subscription_id, collection_id)
);
CREATE INDEX IF NOT EXISTS idx_streaming_push_follows_series ON public.streaming_push_follows (collection_id);

-- The days each viewer used RuengFlix while signed in, for retention (came back on day 1, day 7…).
CREATE TABLE IF NOT EXISTS public.streaming_viewer_days (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    day date NOT NULL,
    PRIMARY KEY (viewer_id, day)
);
