-- Managing RuengFlix from the admin portal.

-- Hero picks: series with a rank fill the home page's hero slider in rank order (1 first);
-- with none ranked, the site shows its newest series there.
ALTER TABLE public.collections
    ADD COLUMN IF NOT EXISTS featured_rank integer;

CREATE INDEX IF NOT EXISTS idx_collections_featured ON public.collections (featured_rank) WHERE featured_rank IS NOT NULL;

-- Every new-episode alert RuengFlix sent to a series' Telegram followers, written by movie-api when
-- RuengFlix reports one. source: AUTO (RuengFlix noticed a new episode) or MANUAL (sent from the admin portal).
CREATE TABLE IF NOT EXISTS public.streaming_alert_log (
    id bigserial PRIMARY KEY,
    collection_id bigint NOT NULL REFERENCES public.collections (id) ON DELETE CASCADE,
    episode integer NOT NULL,
    source varchar(10) NOT NULL,
    followers integer NOT NULL DEFAULT 0,
    sent integer NOT NULL DEFAULT 0,
    removed integer NOT NULL DEFAULT 0,
    failed integer NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_streaming_alert_log_series ON public.streaming_alert_log (collection_id, created_at DESC);
