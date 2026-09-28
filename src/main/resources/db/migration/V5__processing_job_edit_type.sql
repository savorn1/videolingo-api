-- V1's check constraint hardcoded the ProcessingJobType values at the time;
-- EDIT (trim/crop/split) needs to be allowed too.
ALTER TABLE public.processing_jobs DROP CONSTRAINT IF EXISTS processing_jobs_type_check;
ALTER TABLE public.processing_jobs ADD CONSTRAINT processing_jobs_type_check CHECK (((type)::text = ANY ((ARRAY['TRANSCODE'::character varying, 'TRANSCRIBE'::character varying, 'TRANSLATE'::character varying, 'GENERATE_SUBTITLES'::character varying, 'GENERATE_THUMBNAIL'::character varying, 'DUB'::character varying, 'DOWNLOAD'::character varying, 'EDIT'::character varying])::text[]))) NOT VALID;
