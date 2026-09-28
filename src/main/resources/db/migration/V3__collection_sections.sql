-- Optional grouping label for a video within a collection (e.g. "Week 1"),
-- shown as a heading wherever the collection's videos are listed.
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS section varchar(200);
