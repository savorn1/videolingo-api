-- Word timings of a transcript segment, as JSON [{startMs,endMs,text}] (from speech-to-text); null when unknown.
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS words text;
