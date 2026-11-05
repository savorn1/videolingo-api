-- RuengFlix: the rows of series on its home page, in order, edited in the admin portal (RuengFlix › Home rows).
-- movie-api reads the active ones for RuengFlix; with none, RuengFlix shows its own built-in rows.

CREATE TABLE IF NOT EXISTS public.streaming_home_rows (
    id bigserial PRIMARY KEY,
    -- The heading viewers see, usually Khmer
    title varchar(80) NOT NULL,
    -- LATEST: newest updates · POPULAR: most watched now · CATEGORY: one series category · GENRE: one genre
    source varchar(20) NOT NULL CHECK (source IN ('LATEST', 'POPULAR', 'CATEGORY', 'GENRE')),
    -- The category (CULTIVATION, ROMANCE, FANTASY) or the genre's name; empty for LATEST and POPULAR
    value varchar(80),
    max_items integer NOT NULL DEFAULT 12 CHECK (max_items BETWEEN 4 AND 30),
    position integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_by varchar(100),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    CHECK ((source IN ('CATEGORY', 'GENRE')) = (value IS NOT NULL AND value <> ''))
);

CREATE INDEX IF NOT EXISTS idx_streaming_home_rows_position ON public.streaming_home_rows (position, id);

-- Seed: the four rows RuengFlix's home page had built in, so it looks the same until someone changes them.
INSERT INTO public.streaming_home_rows (title, source, value, max_items, position, created_by)
SELECT t.title, t.source, t.value, t.max_items, t.position, 'seed'
FROM (VALUES
    ('អាប់ដេតថ្មី', 'LATEST', NULL, 12, 0),
    ('ក្បាច់គុន និងពិភពអមតៈ', 'CATEGORY', 'CULTIVATION', 12, 1),
    ('ស្នេហា និងមនោសញ្ចេតនា', 'CATEGORY', 'ROMANCE', 12, 2),
    ('ពិភពស្រមើស្រមៃ និងអព្ភូតហេតុ', 'CATEGORY', 'FANTASY', 12, 3)
) AS t (title, source, value, max_items, position)
WHERE NOT EXISTS (SELECT 1 FROM public.streaming_home_rows);
