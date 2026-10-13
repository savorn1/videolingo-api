-- A marker can also be a chapter learners see on the watch page (and jump between).
ALTER TABLE public.video_markers ADD COLUMN IF NOT EXISTS chapter boolean NOT NULL DEFAULT false;
