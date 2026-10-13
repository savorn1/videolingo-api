-- When a disabled video is to be enabled by itself (null: not scheduled).
-- VideoPublisher looks once a minute for videos whose time has come.
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS publish_at timestamp;
CREATE INDEX IF NOT EXISTS idx_videos_publish_at ON public.videos (publish_at) WHERE publish_at IS NOT NULL;
