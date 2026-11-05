-- RuengFlix growth and support (read and written by movie-api; VideoLingo owns the schema).

-- Orders: when the viewer saw the "your payment went through" notice, and refunds made by an admin
-- (status REFUNDED; what was delivered is taken back, the money is returned outside RuengFlix).
ALTER TABLE public.streaming_orders
    ADD COLUMN IF NOT EXISTS seen_at timestamp,
    ADD COLUMN IF NOT EXISTS refunded_at timestamp,
    ADD COLUMN IF NOT EXISTS refunded_by varchar(100),
    ADD COLUMN IF NOT EXISTS refund_reason varchar(300);

-- The VIP end date a "VIP ends soon" reminder was last sent for (one reminder per end date)
ALTER TABLE public.streaming_wallets ADD COLUMN IF NOT EXISTS vip_reminded_for timestamp;

-- Episodes a signed-in viewer finished, per local day: daily missions and invite rewards count them
CREATE TABLE IF NOT EXISTS public.streaming_viewer_watches (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    day date NOT NULL,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_id, day, collection_id, episode)
);

-- Daily mission rewards collected (one per mission per day)
CREATE TABLE IF NOT EXISTS public.streaming_mission_claims (
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    day date NOT NULL,
    mission varchar(30) NOT NULL,
    coins integer NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_id, day, mission)
);

-- Invite a friend: each viewer's code, and who invited whom (rewarded once the friend has watched enough)
ALTER TABLE public.streaming_viewers ADD COLUMN IF NOT EXISTS invite_code varchar(16);
CREATE UNIQUE INDEX IF NOT EXISTS uq_streaming_viewers_invite_code ON public.streaming_viewers (invite_code);

CREATE TABLE IF NOT EXISTS public.streaming_referrals (
    invitee_id bigint PRIMARY KEY REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    inviter_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    created_at timestamp NOT NULL DEFAULT now(),
    rewarded_at timestamp,
    CONSTRAINT streaming_referrals_not_self CHECK (invitee_id <> inviter_id)
);
CREATE INDEX IF NOT EXISTS idx_streaming_referrals_inviter ON public.streaming_referrals (inviter_id);

-- Emoji reactions along an episode's timeline, counted per 5-second bucket
CREATE TABLE IF NOT EXISTS public.streaming_episode_reactions (
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    bucket integer NOT NULL,
    kind varchar(10) NOT NULL,
    count integer NOT NULL DEFAULT 0,
    PRIMARY KEY (collection_id, episode, bucket, kind)
);

-- What viewers search for, per day, for "trending searches"
CREATE TABLE IF NOT EXISTS public.streaming_search_terms (
    term varchar(60) NOT NULL,
    day date NOT NULL,
    count integer NOT NULL DEFAULT 0,
    PRIMARY KEY (term, day)
);

-- Official replies from RuengFlix (written in the admin portal) under a viewer's comment
ALTER TABLE public.streaming_comments
    ALTER COLUMN viewer_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS parent_id bigint REFERENCES public.streaming_comments (id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS author_admin varchar(100);
ALTER TABLE public.streaming_comments DROP CONSTRAINT IF EXISTS streaming_comments_author;
ALTER TABLE public.streaming_comments
    ADD CONSTRAINT streaming_comments_author CHECK (viewer_id IS NOT NULL OR author_admin IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_streaming_comments_parent ON public.streaming_comments (parent_id) WHERE parent_id IS NOT NULL;
