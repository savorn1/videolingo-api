-- A collection can also be a series on the public streaming site (RuengFlix):
-- its videos, in order, are the episodes. These columns hold the site's own
-- settings; nothing is public until `streaming` is turned on.
ALTER TABLE public.collections
    ADD COLUMN IF NOT EXISTS streaming boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS series_category varchar(20),
    ADD COLUMN IF NOT EXISTS series_status varchar(20) NOT NULL DEFAULT 'ONGOING',
    ADD COLUMN IF NOT EXISTS vip boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS free_episodes integer NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS recap_seconds integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS search_keywords varchar(200),
    ADD COLUMN IF NOT EXISTS region varchar(40),
    ADD COLUMN IF NOT EXISTS release_year integer,
    ADD COLUMN IF NOT EXISTS genres varchar(400),
    ADD COLUMN IF NOT EXISTS banner_url varchar(1000),
    ADD COLUMN IF NOT EXISTS vertical_url varchar(1000);

CREATE INDEX IF NOT EXISTS idx_collections_streaming ON public.collections (streaming) WHERE streaming;
