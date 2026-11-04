-- What viewers do on the public streaming site (RuengFlix, through movie-api), kept per series (collection) and
-- episode number. Nothing here identifies a viewer except a Telegram chat id they chose to
-- follow a series with. Deleting a collection deletes its rows.

-- Per-episode counters: plays started, plays finished (90%), 👍 and 👎.
CREATE TABLE IF NOT EXISTS public.streaming_episode_stats (
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    starts bigint NOT NULL DEFAULT 0,
    completes bigint NOT NULL DEFAULT 0,
    likes bigint NOT NULL DEFAULT 0,
    dislikes bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (collection_id, episode)
);

-- "Report a problem" from the player.
CREATE TABLE IF NOT EXISTS public.streaming_reports (
    id bigserial PRIMARY KEY,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    reason varchar(20) NOT NULL,
    note varchar(300) NOT NULL DEFAULT '',
    position_seconds integer NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now(),
    resolved_at timestamp,
    resolved_by varchar(100)
);
CREATE INDEX IF NOT EXISTS idx_streaming_reports_open ON public.streaming_reports (created_at DESC) WHERE resolved_at IS NULL;

-- Telegram chats following a series for new-episode alerts.
CREATE TABLE IF NOT EXISTS public.streaming_follows (
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    chat_id bigint NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, chat_id)
);
CREATE INDEX IF NOT EXISTS idx_streaming_follows_chat ON public.streaming_follows (chat_id);

-- The episode count followers were last alerted about, so each new episode is announced once
-- even with several site instances.
CREATE TABLE IF NOT EXISTS public.streaming_alert_marks (
    collection_id bigint PRIMARY KEY REFERENCES public.collections (id) ON DELETE CASCADE,
    episodes integer NOT NULL
);
