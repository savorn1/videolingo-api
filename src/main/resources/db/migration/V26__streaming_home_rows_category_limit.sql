-- RuengFlix's category rows showed every series in their category before V25; the seeded ones took 12.
-- Give the seeded category rows the most a row takes (30), unless someone has changed them since.
UPDATE public.streaming_home_rows
SET max_items = 30, updated_at = now()
WHERE created_by = 'seed' AND source = 'CATEGORY' AND max_items = 12 AND updated_at = created_at;
