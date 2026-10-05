-- Exact lengths: duration_seconds is rounded, which is up to half a second off
-- for trim limits and extended-trim padding. Left NULL on existing rows (they
-- fall back to duration_seconds) and filled in the next time the file is read.
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.video_clips ADD COLUMN IF NOT EXISTS duration_ms bigint;

-- The video file an edit result was made from, so applying it can refuse when
-- the video has been replaced since (it would silently undo that change).
ALTER TABLE public.video_clips ADD COLUMN IF NOT EXISTS source_key varchar(500);
