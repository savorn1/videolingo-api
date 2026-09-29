-- Audio edits (operation AUDIO) and audio extracts (EXTRACT) are also
-- VideoClips; `summary` says what an audio edit changed, for the review list.
ALTER TABLE public.video_clips ADD COLUMN IF NOT EXISTS summary varchar(300);
