-- A split part's own name (optional), used in its new video's title instead of "— Part N".
ALTER TABLE public.video_clips ADD COLUMN IF NOT EXISTS label varchar(80);
