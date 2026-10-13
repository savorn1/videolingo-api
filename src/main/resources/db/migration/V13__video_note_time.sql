-- A review note can be about a moment of the video (null: about the whole video).
ALTER TABLE public.video_notes ADD COLUMN IF NOT EXISTS at_ms bigint;
