-- RuengFlix: rewarded ads, promotions, gifts, paywall analytics, "you stopped at…" reminders,
-- time-stamped comments, and shareable clips. movie-api reads and writes these; VideoLingo owns the
-- schema, edits ads and promotions in the admin portal, and cuts the clips.

-- Sponsor videos a viewer watches to unlock one VIP episode. `seconds` is how long they must watch.
CREATE TABLE IF NOT EXISTS public.streaming_ads (
    id bigserial PRIMARY KEY,
    title varchar(120) NOT NULL,
    video_url varchar(1000) NOT NULL,
    -- Optional "Learn more" link for the sponsor
    click_url varchar(1000),
    seconds integer NOT NULL DEFAULT 15 CHECK (seconds BETWEEN 5 AND 60),
    active boolean NOT NULL DEFAULT true,
    -- Higher shows more often among the active ads
    weight integer NOT NULL DEFAULT 1 CHECK (weight BETWEEN 1 AND 100),
    created_by varchar(100),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);

-- One ad shown to a viewer for one episode. Completed only once they watched long enough (checked on
-- the server against started_at), and then the episode is theirs (an entitlement with source AD).
CREATE TABLE IF NOT EXISTS public.streaming_ad_views (
    id bigserial PRIMARY KEY,
    ad_id bigint REFERENCES public.streaming_ads (id) ON DELETE SET NULL,
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    -- Local day, for the daily limit
    day date NOT NULL,
    started_at timestamp NOT NULL,
    completed_at timestamp
);
CREATE INDEX IF NOT EXISTS idx_streaming_ad_views_viewer_day ON public.streaming_ad_views (viewer_id, day);
CREATE INDEX IF NOT EXISTS idx_streaming_ad_views_ad ON public.streaming_ad_views (ad_id);

-- Time-limited offers: a VIP series free for everyone, or extra coins on every coin pack.
CREATE TABLE IF NOT EXISTS public.streaming_promotions (
    id bigserial PRIMARY KEY,
    kind varchar(15) NOT NULL CHECK (kind IN ('FREE_SERIES', 'COIN_BONUS')),
    -- Shown on the site's banner
    title varchar(120) NOT NULL,
    collection_id bigint REFERENCES public.collections (id) ON DELETE CASCADE,
    bonus_percent integer,
    starts_at timestamp NOT NULL,
    ends_at timestamp NOT NULL,
    created_by varchar(100),
    created_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT streaming_promotions_window CHECK (ends_at > starts_at),
    CONSTRAINT streaming_promotions_series CHECK (kind <> 'FREE_SERIES' OR collection_id IS NOT NULL),
    CONSTRAINT streaming_promotions_bonus CHECK (kind <> 'COIN_BONUS' OR bonus_percent BETWEEN 1 AND 200)
);
CREATE INDEX IF NOT EXISTS idx_streaming_promotions_window ON public.streaming_promotions (ends_at, starts_at);

-- What a promotion added to an order (extra coins), kept like the first top-up bonus
ALTER TABLE public.streaming_orders ADD COLUMN IF NOT EXISTS promo_coins integer;

-- Gifts: an order bought for someone else. Paying it makes a code instead of delivering to the buyer;
-- whoever redeems the code gets the coins or VIP time, once.
ALTER TABLE public.streaming_orders ADD COLUMN IF NOT EXISTS gift boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS public.streaming_gifts (
    id bigserial PRIMARY KEY,
    code varchar(16) NOT NULL UNIQUE,
    order_id bigint NOT NULL UNIQUE REFERENCES public.streaming_orders (id) ON DELETE CASCADE,
    giver_id bigint REFERENCES public.streaming_viewers (id) ON DELETE SET NULL,
    kind varchar(10) NOT NULL CHECK (kind IN ('COINS', 'VIP')),
    coins integer,
    months integer,
    message varchar(140),
    created_at timestamp NOT NULL DEFAULT now(),
    redeemed_by bigint REFERENCES public.streaming_viewers (id) ON DELETE SET NULL,
    redeemed_at timestamp,
    -- Refunded before anyone redeemed it
    voided_at timestamp
);
CREATE INDEX IF NOT EXISTS idx_streaming_gifts_giver ON public.streaming_gifts (giver_id, created_at DESC);

-- Paywall analytics: how often the unlock screen was shown for each episode
ALTER TABLE public.streaming_episode_stats ADD COLUMN IF NOT EXISTS wall_views bigint NOT NULL DEFAULT 0;

-- "You stopped at episode N" reminders already sent (one per viewer per episode)
CREATE TABLE IF NOT EXISTS public.streaming_resume_reminders (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    sent_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_id, collection_id, episode)
);

-- Comments written while watching keep the moment (seconds into the episode), to float over the video
ALTER TABLE public.streaming_comments
    ADD COLUMN IF NOT EXISTS at_seconds integer CHECK (at_seconds IS NULL OR at_seconds >= 0);
CREATE INDEX IF NOT EXISTS idx_streaming_comments_timed
    ON public.streaming_comments (collection_id, episode, at_seconds) WHERE at_seconds IS NOT NULL;

-- Shareable clips (up to 15 s) cut from an episode by VideoLingo's clip worker.
-- status PENDING → READY (url set) or FAILED (error set).
CREATE TABLE IF NOT EXISTS public.streaming_clips (
    id bigserial PRIMARY KEY,
    code varchar(16) NOT NULL UNIQUE,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    video_id bigint NOT NULL REFERENCES public.videos (id) ON DELETE CASCADE,
    start_seconds integer NOT NULL CHECK (start_seconds >= 0),
    seconds integer NOT NULL CHECK (seconds BETWEEN 3 AND 15),
    viewer_id bigint REFERENCES public.streaming_viewers (id) ON DELETE SET NULL,
    status varchar(10) NOT NULL DEFAULT 'PENDING',
    url varchar(1000),
    storage_key varchar(500),
    poster_url varchar(1000),
    error varchar(300),
    views integer NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now(),
    finished_at timestamp
);
CREATE INDEX IF NOT EXISTS idx_streaming_clips_pending ON public.streaming_clips (created_at) WHERE status = 'PENDING';
-- The same moment asked for twice reuses one clip
CREATE UNIQUE INDEX IF NOT EXISTS uq_streaming_clips_moment
    ON public.streaming_clips (video_id, start_seconds, seconds) WHERE status <> 'FAILED';
