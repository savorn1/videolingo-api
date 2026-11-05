-- RuengFlix extras (read and written by movie-api; VideoLingo owns the schema):
-- comment likes and pinned comments, and the first-purchase bonus recorded on each coin order.

ALTER TABLE public.streaming_comments
    ADD COLUMN IF NOT EXISTS like_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pinned_at timestamp,
    ADD COLUMN IF NOT EXISTS pinned_by varchar(100);

CREATE TABLE IF NOT EXISTS public.streaming_comment_likes (
    comment_id bigint NOT NULL REFERENCES public.streaming_comments (id) ON DELETE CASCADE,
    viewer_id bigint NOT NULL REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    created_at timestamp NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, viewer_id)
);

-- Coins added on top of a pack (first top-up bonus); null when none
ALTER TABLE public.streaming_orders ADD COLUMN IF NOT EXISTS bonus_coins integer;
