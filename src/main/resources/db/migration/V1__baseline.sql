-- Baseline schema, generated from the JPA entities (pg_dump of a schema
-- Hibernate built), rewritten to be idempotent: every table, column, index
-- and constraint is created only if it's missing. So it runs on an empty
-- database, and it also brings a database that ddl-auto=update used to
-- manage up to date (Flyway baselines those at version 0, then runs this).
-- Enum CHECK constraints are dropped and re-added, because ddl-auto never
-- refreshed their value lists; they're NOT VALID so old rows aren't re-checked.
--
-- New schema changes go in new files: V2__what_changed.sql, V3__…


-- ai_chat_messages
CREATE TABLE IF NOT EXISTS public.ai_chat_messages (
    id bigint NOT NULL,
    chat_id bigint NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    role character varying(10) NOT NULL,
    usage_id bigint
);
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS chat_id bigint;
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS content text;
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS role character varying(10);
ALTER TABLE public.ai_chat_messages ADD COLUMN IF NOT EXISTS usage_id bigint;
ALTER TABLE public.ai_chat_messages DROP CONSTRAINT IF EXISTS ai_chat_messages_role_check;
ALTER TABLE public.ai_chat_messages ADD CONSTRAINT ai_chat_messages_role_check CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying])::text[]))) NOT VALID;

-- ai_chats
CREATE TABLE IF NOT EXISTS public.ai_chats (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    message_count integer NOT NULL,
    title character varying(120) NOT NULL,
    transcript_id bigint NOT NULL,
    updated_at timestamp(6) without time zone,
    video_id bigint NOT NULL
);
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS message_count integer;
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS title character varying(120);
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS transcript_id bigint;
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.ai_chats ADD COLUMN IF NOT EXISTS video_id bigint;

-- ai_generations
CREATE TABLE IF NOT EXISTS public.ai_generations (
    id bigint NOT NULL,
    content_json text NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(100),
    item_count integer NOT NULL,
    model character varying(60) NOT NULL,
    output_language character varying(10) NOT NULL,
    transcript_id bigint NOT NULL,
    type character varying(20) NOT NULL,
    usage_id bigint,
    video_id bigint NOT NULL,
    warnings text
);
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS content_json text;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS item_count integer;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS model character varying(60);
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS output_language character varying(10);
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS transcript_id bigint;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS type character varying(20);
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS usage_id bigint;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.ai_generations ADD COLUMN IF NOT EXISTS warnings text;
ALTER TABLE public.ai_generations DROP CONSTRAINT IF EXISTS ai_generations_type_check;
ALTER TABLE public.ai_generations ADD CONSTRAINT ai_generations_type_check CHECK (((type)::text = ANY ((ARRAY['SUMMARY'::character varying, 'CHAPTERS'::character varying, 'KEY_POINTS'::character varying, 'QUESTIONS'::character varying, 'QUIZ'::character varying, 'CHAT'::character varying, 'TRANSLATION'::character varying, 'LOOKUP'::character varying])::text[]))) NOT VALID;

-- ai_usage
CREATE TABLE IF NOT EXISTS public.ai_usage (
    id bigint NOT NULL,
    cache_read_tokens bigint NOT NULL,
    cache_write_tokens bigint NOT NULL,
    chat_id bigint,
    cost_usd numeric(14,6),
    created_at timestamp(6) without time zone NOT NULL,
    error_message character varying(1000),
    fallback_from character varying(60),
    feature character varying(20) NOT NULL,
    input_tokens bigint NOT NULL,
    latency_ms bigint,
    model character varying(60) NOT NULL,
    output_tokens bigint NOT NULL,
    request_id character varying(100),
    status character varying(20) NOT NULL,
    stop_reason character varying(30),
    transcript_id bigint,
    username character varying(100),
    video_id bigint
);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS cache_read_tokens bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS cache_write_tokens bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS chat_id bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS cost_usd numeric(14,6);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS error_message character varying(1000);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS fallback_from character varying(60);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS feature character varying(20);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS input_tokens bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS latency_ms bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS model character varying(60);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS output_tokens bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS request_id character varying(100);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS status character varying(20);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS stop_reason character varying(30);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS transcript_id bigint;
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS username character varying(100);
ALTER TABLE public.ai_usage ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.ai_usage DROP CONSTRAINT IF EXISTS ai_usage_feature_check;
ALTER TABLE public.ai_usage ADD CONSTRAINT ai_usage_feature_check CHECK (((feature)::text = ANY ((ARRAY['SUMMARY'::character varying, 'CHAPTERS'::character varying, 'KEY_POINTS'::character varying, 'QUESTIONS'::character varying, 'QUIZ'::character varying, 'CHAT'::character varying, 'TRANSLATION'::character varying, 'LOOKUP'::character varying])::text[]))) NOT VALID;
ALTER TABLE public.ai_usage DROP CONSTRAINT IF EXISTS ai_usage_status_check;
ALTER TABLE public.ai_usage ADD CONSTRAINT ai_usage_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'REFUSED'::character varying, 'TRUNCATED'::character varying, 'ERROR'::character varying])::text[]))) NOT VALID;

-- api_keys
CREATE TABLE IF NOT EXISTS public.api_keys (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    expires_at timestamp(6) without time zone,
    key_hash character varying(64) NOT NULL,
    last_used_at timestamp(6) without time zone,
    name character varying(100) NOT NULL,
    prefix character varying(20) NOT NULL,
    revoked_at timestamp(6) without time zone,
    revoked_by character varying(100),
    user_id bigint NOT NULL,
    username character varying(100) NOT NULL
);
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS expires_at timestamp(6) without time zone;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS key_hash character varying(64);
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS last_used_at timestamp(6) without time zone;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS prefix character varying(20);
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS revoked_at timestamp(6) without time zone;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS revoked_by character varying(100);
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE public.api_keys ADD COLUMN IF NOT EXISTS username character varying(100);

-- app_settings
CREATE TABLE IF NOT EXISTS public.app_settings (
    section character varying(40) NOT NULL,
    updated_at timestamp(6) without time zone,
    updated_by character varying(50),
    value_json text NOT NULL,
    version bigint NOT NULL
);
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS section character varying(40);
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS updated_by character varying(50);
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS value_json text;
ALTER TABLE public.app_settings ADD COLUMN IF NOT EXISTS version bigint;

-- audit_logs
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id bigint NOT NULL,
    action character varying(20),
    auth_type character varying(20),
    created_at timestamp(6) without time zone NOT NULL,
    detail character varying(300),
    duration_ms bigint NOT NULL,
    entity_id bigint,
    ip_address character varying(64),
    method character varying(10) NOT NULL,
    module character varying(60) NOT NULL,
    path character varying(500) NOT NULL,
    status integer NOT NULL,
    user_agent character varying(300),
    username character varying(100)
);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS action character varying(20);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS auth_type character varying(20);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS detail character varying(300);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS entity_id bigint;
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS ip_address character varying(64);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS method character varying(10);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS module character varying(60);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS path character varying(500);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS status integer;
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS user_agent character varying(300);
ALTER TABLE public.audit_logs ADD COLUMN IF NOT EXISTS username character varying(100);

-- categories
CREATE TABLE IF NOT EXISTS public.categories (
    id bigint NOT NULL,
    color character varying(20),
    created_at timestamp(6) without time zone,
    description character varying(500),
    enabled boolean NOT NULL,
    name character varying(100) NOT NULL,
    slug character varying(120) NOT NULL,
    sort_order integer NOT NULL,
    updated_at timestamp(6) without time zone
);
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS color character varying(20);
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS description character varying(500);
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS slug character varying(120);
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS sort_order integer;
ALTER TABLE public.categories ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;

-- collection_items
CREATE TABLE IF NOT EXISTS public.collection_items (
    id bigint NOT NULL,
    added_at timestamp(6) without time zone NOT NULL,
    added_by character varying(100),
    collection_id bigint NOT NULL,
    "position" integer NOT NULL,
    video_id bigint NOT NULL
);
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS added_at timestamp(6) without time zone;
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS added_by character varying(100);
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS collection_id bigint;
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS "position" integer;
ALTER TABLE public.collection_items ADD COLUMN IF NOT EXISTS video_id bigint;

-- collections
CREATE TABLE IF NOT EXISTS public.collections (
    id bigint NOT NULL,
    cover_url character varying(1000),
    created_at timestamp(6) without time zone,
    description character varying(2000),
    owner_id bigint,
    slug character varying(120) NOT NULL,
    title character varying(200) NOT NULL,
    updated_at timestamp(6) without time zone,
    video_count integer NOT NULL,
    visibility character varying(20) NOT NULL
);
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS cover_url character varying(1000);
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS description character varying(2000);
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS owner_id bigint;
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS slug character varying(120);
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS title character varying(200);
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS video_count integer;
ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS visibility character varying(20);
ALTER TABLE public.collections DROP CONSTRAINT IF EXISTS collections_visibility_check;
ALTER TABLE public.collections ADD CONSTRAINT collections_visibility_check CHECK (((visibility)::text = ANY ((ARRAY['PUBLIC'::character varying, 'UNLISTED'::character varying, 'PRIVATE'::character varying])::text[]))) NOT VALID;

-- content_revisions
CREATE TABLE IF NOT EXISTS public.content_revisions (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    entity_id bigint NOT NULL,
    entity_type character varying(20) NOT NULL,
    item_count integer NOT NULL,
    number integer NOT NULL,
    snapshot text NOT NULL,
    summary character varying(200) NOT NULL
);
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS entity_id bigint;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS entity_type character varying(20);
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS item_count integer;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS number integer;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS snapshot text;
ALTER TABLE public.content_revisions ADD COLUMN IF NOT EXISTS summary character varying(200);
ALTER TABLE public.content_revisions DROP CONSTRAINT IF EXISTS content_revisions_entity_type_check;
ALTER TABLE public.content_revisions ADD CONSTRAINT content_revisions_entity_type_check CHECK (((entity_type)::text = ANY ((ARRAY['SUBTITLE'::character varying, 'TRANSCRIPT'::character varying])::text[]))) NOT VALID;

-- custom_roles
CREATE TABLE IF NOT EXISTS public.custom_roles (
    id bigint NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL
);
ALTER TABLE public.custom_roles ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.custom_roles ADD COLUMN IF NOT EXISTS description character varying(255);
ALTER TABLE public.custom_roles ADD COLUMN IF NOT EXISTS name character varying(255);

-- glossaries
CREATE TABLE IF NOT EXISTS public.glossaries (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    description character varying(300),
    enabled boolean NOT NULL,
    name character varying(100) NOT NULL,
    source_language character varying(10),
    target_language character varying(10) NOT NULL,
    term_count integer NOT NULL,
    updated_at timestamp(6) without time zone,
    updated_by character varying(100)
);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS description character varying(300);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS source_language character varying(10);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS target_language character varying(10);
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS term_count integer;
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.glossaries ADD COLUMN IF NOT EXISTS updated_by character varying(100);

-- glossary_terms
CREATE TABLE IF NOT EXISTS public.glossary_terms (
    id bigint NOT NULL,
    case_sensitive boolean NOT NULL,
    do_not_translate boolean NOT NULL,
    glossary_id bigint NOT NULL,
    note character varying(300),
    "position" integer NOT NULL,
    source character varying(200) NOT NULL,
    target character varying(200) NOT NULL
);
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS case_sensitive boolean;
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS do_not_translate boolean;
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS glossary_id bigint;
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS note character varying(300);
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS "position" integer;
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS source character varying(200);
ALTER TABLE public.glossary_terms ADD COLUMN IF NOT EXISTS target character varying(200);

-- languages
CREATE TABLE IF NOT EXISTS public.languages (
    id bigint NOT NULL,
    code character varying(10) NOT NULL,
    created_at timestamp(6) without time zone,
    enabled boolean NOT NULL,
    is_default boolean NOT NULL,
    name character varying(100) NOT NULL,
    native_name character varying(100),
    updated_at timestamp(6) without time zone
);
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS code character varying(10);
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS is_default boolean;
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS native_name character varying(100);
ALTER TABLE public.languages ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;

-- notification_batches
CREATE TABLE IF NOT EXISTS public.notification_batches (
    id bigint NOT NULL,
    audience character varying(100) NOT NULL,
    channels character varying(40) NOT NULL,
    created_at timestamp(6) without time zone,
    recipient_count integer NOT NULL,
    sent_by character varying(50),
    subject character varying(200) NOT NULL,
    template_code character varying(60),
    template_id bigint,
    template_name character varying(100)
);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS audience character varying(100);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS channels character varying(40);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS recipient_count integer;
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS sent_by character varying(50);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS subject character varying(200);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS template_code character varying(60);
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS template_id bigint;
ALTER TABLE public.notification_batches ADD COLUMN IF NOT EXISTS template_name character varying(100);

-- notification_events
CREATE TABLE IF NOT EXISTS public.notification_events (
    id bigint NOT NULL,
    actor character varying(50),
    created_at timestamp(6) without time zone,
    detail character varying(1000),
    notification_id bigint NOT NULL,
    type character varying(20) NOT NULL
);
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS actor character varying(50);
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS detail character varying(1000);
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS notification_id bigint;
ALTER TABLE public.notification_events ADD COLUMN IF NOT EXISTS type character varying(20);
ALTER TABLE public.notification_events DROP CONSTRAINT IF EXISTS notification_events_type_check;
ALTER TABLE public.notification_events ADD CONSTRAINT notification_events_type_check CHECK (((type)::text = ANY ((ARRAY['CREATED'::character varying, 'SENT'::character varying, 'FAILED'::character varying, 'RETRIED'::character varying, 'READ'::character varying])::text[]))) NOT VALID;

-- notification_template_channels
CREATE TABLE IF NOT EXISTS public.notification_template_channels (
    template_id bigint NOT NULL,
    channel character varying(20) NOT NULL
);
ALTER TABLE public.notification_template_channels ADD COLUMN IF NOT EXISTS template_id bigint;
ALTER TABLE public.notification_template_channels ADD COLUMN IF NOT EXISTS channel character varying(20);
ALTER TABLE public.notification_template_channels DROP CONSTRAINT IF EXISTS notification_template_channels_channel_check;
ALTER TABLE public.notification_template_channels ADD CONSTRAINT notification_template_channels_channel_check CHECK (((channel)::text = ANY ((ARRAY['IN_APP'::character varying, 'EMAIL'::character varying])::text[]))) NOT VALID;

-- notification_templates
CREATE TABLE IF NOT EXISTS public.notification_templates (
    id bigint NOT NULL,
    body text NOT NULL,
    code character varying(60) NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(50),
    description character varying(300),
    name character varying(100) NOT NULL,
    subject character varying(200) NOT NULL,
    updated_at timestamp(6) without time zone,
    updated_by character varying(50)
);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS body text;
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS code character varying(60);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS created_by character varying(50);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS description character varying(300);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS subject character varying(200);
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.notification_templates ADD COLUMN IF NOT EXISTS updated_by character varying(50);

-- notifications
CREATE TABLE IF NOT EXISTS public.notifications (
    id bigint NOT NULL,
    attempts integer NOT NULL,
    batch_id bigint NOT NULL,
    body text NOT NULL,
    channel character varying(20) NOT NULL,
    created_at timestamp(6) without time zone,
    error_message character varying(1000),
    read_at timestamp(6) without time zone,
    recipient_email character varying(255),
    recipient_id bigint,
    recipient_username character varying(50) NOT NULL,
    sent_at timestamp(6) without time zone,
    sent_by character varying(50),
    status character varying(20) NOT NULL,
    subject character varying(300) NOT NULL,
    template_code character varying(60),
    template_id bigint
);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS attempts integer;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS batch_id bigint;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS body text;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS channel character varying(20);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS error_message character varying(1000);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS read_at timestamp(6) without time zone;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS recipient_email character varying(255);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS recipient_id bigint;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS recipient_username character varying(50);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS sent_at timestamp(6) without time zone;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS sent_by character varying(50);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS status character varying(20);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS subject character varying(300);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS template_code character varying(60);
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS template_id bigint;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_channel_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_channel_check CHECK (((channel)::text = ANY ((ARRAY['IN_APP'::character varying, 'EMAIL'::character varying])::text[]))) NOT VALID;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_status_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'FAILED'::character varying])::text[]))) NOT VALID;

-- processing_job_logs
CREATE TABLE IF NOT EXISTS public.processing_job_logs (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    job_id bigint NOT NULL,
    level character varying(10) NOT NULL,
    message text NOT NULL
);
ALTER TABLE public.processing_job_logs ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.processing_job_logs ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.processing_job_logs ADD COLUMN IF NOT EXISTS job_id bigint;
ALTER TABLE public.processing_job_logs ADD COLUMN IF NOT EXISTS level character varying(10);
ALTER TABLE public.processing_job_logs ADD COLUMN IF NOT EXISTS message text;
ALTER TABLE public.processing_job_logs DROP CONSTRAINT IF EXISTS processing_job_logs_level_check;
ALTER TABLE public.processing_job_logs ADD CONSTRAINT processing_job_logs_level_check CHECK (((level)::text = ANY ((ARRAY['DEBUG'::character varying, 'INFO'::character varying, 'WARN'::character varying, 'ERROR'::character varying])::text[]))) NOT VALID;

-- processing_jobs
CREATE TABLE IF NOT EXISTS public.processing_jobs (
    id bigint NOT NULL,
    attempts integer NOT NULL,
    created_at timestamp(6) without time zone,
    current_step character varying(200),
    error_message text,
    finished_at timestamp(6) without time zone,
    max_attempts integer NOT NULL,
    parameters text,
    progress integer NOT NULL,
    started_at timestamp(6) without time zone,
    status character varying(20) NOT NULL,
    type character varying(40) NOT NULL,
    updated_at timestamp(6) without time zone,
    version bigint NOT NULL,
    video_id bigint NOT NULL
);
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS attempts integer;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS current_step character varying(200);
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS error_message text;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS finished_at timestamp(6) without time zone;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS max_attempts integer;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS parameters text;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS progress integer;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS started_at timestamp(6) without time zone;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS status character varying(20);
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS type character varying(40);
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE public.processing_jobs ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.processing_jobs DROP CONSTRAINT IF EXISTS processing_jobs_status_check;
ALTER TABLE public.processing_jobs ADD CONSTRAINT processing_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))) NOT VALID;
ALTER TABLE public.processing_jobs DROP CONSTRAINT IF EXISTS processing_jobs_type_check;
ALTER TABLE public.processing_jobs ADD CONSTRAINT processing_jobs_type_check CHECK (((type)::text = ANY ((ARRAY['TRANSCODE'::character varying, 'TRANSCRIBE'::character varying, 'TRANSLATE'::character varying, 'GENERATE_SUBTITLES'::character varying, 'GENERATE_THUMBNAIL'::character varying, 'DUB'::character varying, 'DOWNLOAD'::character varying])::text[]))) NOT VALID;

-- quiz_attempts
CREATE TABLE IF NOT EXISTS public.quiz_attempts (
    id bigint NOT NULL,
    answers text NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    generation_id bigint NOT NULL,
    score integer NOT NULL,
    total integer NOT NULL,
    user_id bigint NOT NULL,
    video_id bigint NOT NULL
);
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS answers text;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS generation_id bigint;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS score integer;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS total integer;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE public.quiz_attempts ADD COLUMN IF NOT EXISTS video_id bigint;

-- refresh_tokens
CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    revoked boolean NOT NULL,
    token character varying(64) NOT NULL,
    user_id bigint NOT NULL
);
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS expires_at timestamp(6) without time zone;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS revoked boolean;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS token character varying(64);
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS user_id bigint;

-- role_permissions
CREATE TABLE IF NOT EXISTS public.role_permissions (
    id bigint NOT NULL,
    action character varying(255) NOT NULL,
    custom_role_id bigint NOT NULL,
    module character varying(255) NOT NULL
);
ALTER TABLE public.role_permissions ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.role_permissions ADD COLUMN IF NOT EXISTS action character varying(255);
ALTER TABLE public.role_permissions ADD COLUMN IF NOT EXISTS custom_role_id bigint;
ALTER TABLE public.role_permissions ADD COLUMN IF NOT EXISTS module character varying(255);
ALTER TABLE public.role_permissions DROP CONSTRAINT IF EXISTS role_permissions_action_check;
ALTER TABLE public.role_permissions ADD CONSTRAINT role_permissions_action_check CHECK (((action)::text = ANY ((ARRAY['READ'::character varying, 'WRITE'::character varying, 'APPROVE'::character varying])::text[]))) NOT VALID;

-- study_cards
CREATE TABLE IF NOT EXISTS public.study_cards (
    id bigint NOT NULL,
    at_ms bigint,
    back character varying(1000) NOT NULL,
    context character varying(500),
    created_at timestamp(6) without time zone NOT NULL,
    due_at timestamp(6) without time zone NOT NULL,
    ease double precision NOT NULL,
    front character varying(300) NOT NULL,
    front_key character varying(300) NOT NULL,
    interval_days integer NOT NULL,
    language character varying(10),
    lapses integer NOT NULL,
    last_reviewed_at timestamp(6) without time zone,
    repetitions integer NOT NULL,
    source character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    video_id bigint
);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS at_ms bigint;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS back character varying(1000);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS context character varying(500);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS due_at timestamp(6) without time zone;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS ease double precision;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS front character varying(300);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS front_key character varying(300);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS interval_days integer;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS language character varying(10);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS lapses integer;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS last_reviewed_at timestamp(6) without time zone;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS repetitions integer;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS source character varying(20);
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE public.study_cards ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.study_cards DROP CONSTRAINT IF EXISTS study_cards_source_check;
ALTER TABLE public.study_cards ADD CONSTRAINT study_cards_source_check CHECK (((source)::text = ANY ((ARRAY['WORD'::character varying, 'KEY_POINT'::character varying, 'MANUAL'::character varying])::text[]))) NOT VALID;

-- subtitle_comments
CREATE TABLE IF NOT EXISTS public.subtitle_comments (
    id bigint NOT NULL,
    at_ms bigint,
    author character varying(100) NOT NULL,
    body character varying(2000) NOT NULL,
    created_at timestamp(6) without time zone,
    cue_text character varying(500),
    resolved boolean NOT NULL,
    resolved_at timestamp(6) without time zone,
    resolved_by character varying(100),
    subtitle_id bigint NOT NULL
);
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS at_ms bigint;
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS author character varying(100);
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS body character varying(2000);
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS cue_text character varying(500);
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS resolved boolean;
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS resolved_at timestamp(6) without time zone;
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS resolved_by character varying(100);
ALTER TABLE public.subtitle_comments ADD COLUMN IF NOT EXISTS subtitle_id bigint;

-- subtitle_cues
CREATE TABLE IF NOT EXISTS public.subtitle_cues (
    id bigint NOT NULL,
    end_ms bigint NOT NULL,
    "position" integer NOT NULL,
    start_ms bigint NOT NULL,
    subtitle_id bigint NOT NULL,
    text text NOT NULL
);
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS end_ms bigint;
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS "position" integer;
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS start_ms bigint;
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS subtitle_id bigint;
ALTER TABLE public.subtitle_cues ADD COLUMN IF NOT EXISTS text text;

-- subtitles
CREATE TABLE IF NOT EXISTS public.subtitles (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    cue_count integer NOT NULL,
    duration_ms bigint NOT NULL,
    is_default boolean NOT NULL,
    issue_count integer NOT NULL,
    kind character varying(20) NOT NULL,
    label character varying(100) NOT NULL,
    language character varying(10) NOT NULL,
    max_chars_per_line integer NOT NULL,
    max_cps double precision NOT NULL,
    max_duration_ms bigint NOT NULL,
    max_lines integer NOT NULL,
    min_duration_ms bigint NOT NULL,
    original_filename character varying(255),
    published boolean NOT NULL,
    review_note character varying(1000),
    review_requested_at timestamp(6) without time zone,
    review_requested_by character varying(100),
    review_status character varying(20),
    reviewed_at timestamp(6) without time zone,
    reviewed_by character varying(100),
    source character varying(20) NOT NULL,
    transcript_id bigint,
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint NOT NULL,
    video_id bigint NOT NULL
);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS cue_count integer;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS is_default boolean;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS issue_count integer;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS kind character varying(20);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS label character varying(100);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS language character varying(10);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS max_chars_per_line integer;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS max_cps double precision;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS max_duration_ms bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS max_lines integer;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS min_duration_ms bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS original_filename character varying(255);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS published boolean;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS review_note character varying(1000);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS review_requested_at timestamp(6) without time zone;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS review_requested_by character varying(100);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS review_status character varying(20);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS reviewed_at timestamp(6) without time zone;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS reviewed_by character varying(100);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS source character varying(20);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS transcript_id bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS updated_by character varying(100);
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE public.subtitles ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.subtitles DROP CONSTRAINT IF EXISTS subtitles_kind_check;
ALTER TABLE public.subtitles ADD CONSTRAINT subtitles_kind_check CHECK (((kind)::text = ANY ((ARRAY['SUBTITLES'::character varying, 'CAPTIONS'::character varying])::text[]))) NOT VALID;
ALTER TABLE public.subtitles DROP CONSTRAINT IF EXISTS subtitles_review_status_check;
ALTER TABLE public.subtitles ADD CONSTRAINT subtitles_review_status_check CHECK (((review_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'IN_REVIEW'::character varying, 'CHANGES_REQUESTED'::character varying, 'APPROVED'::character varying])::text[]))) NOT VALID;
ALTER TABLE public.subtitles DROP CONSTRAINT IF EXISTS subtitles_source_check;
ALTER TABLE public.subtitles ADD CONSTRAINT subtitles_source_check CHECK (((source)::text = ANY ((ARRAY['GENERATED'::character varying, 'UPLOADED'::character varying, 'MANUAL'::character varying])::text[]))) NOT VALID;

-- tags
CREATE TABLE IF NOT EXISTS public.tags (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    description character varying(300),
    name character varying(50) NOT NULL,
    slug character varying(60) NOT NULL,
    updated_at timestamp(6) without time zone
);
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS description character varying(300);
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS name character varying(50);
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS slug character varying(60);
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;

-- transcript_segments
CREATE TABLE IF NOT EXISTS public.transcript_segments (
    id bigint NOT NULL,
    end_ms bigint NOT NULL,
    "position" integer NOT NULL,
    speaker character varying(64),
    start_ms bigint NOT NULL,
    text text NOT NULL,
    transcript_id bigint NOT NULL
);
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS end_ms bigint;
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS "position" integer;
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS speaker character varying(64);
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS start_ms bigint;
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS text text;
ALTER TABLE public.transcript_segments ADD COLUMN IF NOT EXISTS transcript_id bigint;

-- transcripts
CREATE TABLE IF NOT EXISTS public.transcripts (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    duration_ms bigint NOT NULL,
    language character varying(10) NOT NULL,
    last_job_id bigint,
    segment_count integer NOT NULL,
    source character varying(20) NOT NULL,
    updated_at timestamp(6) without time zone,
    updated_by character varying(100),
    version bigint NOT NULL,
    video_id bigint NOT NULL,
    word_count integer NOT NULL
);
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS language character varying(10);
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS last_job_id bigint;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS segment_count integer;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS source character varying(20);
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS updated_by character varying(100);
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.transcripts ADD COLUMN IF NOT EXISTS word_count integer;
ALTER TABLE public.transcripts DROP CONSTRAINT IF EXISTS transcripts_source_check;
ALTER TABLE public.transcripts ADD CONSTRAINT transcripts_source_check CHECK (((source)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying, 'IMPORTED'::character varying])::text[]))) NOT VALID;

-- users
CREATE TABLE IF NOT EXISTS public.users (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    custom_role_id bigint,
    email character varying(255),
    enabled boolean NOT NULL,
    last_login_at timestamp(6) without time zone,
    password character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    username character varying(255) NOT NULL
);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS custom_role_id bigint;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email character varying(255);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS last_login_at timestamp(6) without time zone;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS password character varying(255);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS role character varying(255);
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS username character varying(255);
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE public.users ADD CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'USER'::character varying])::text[]))) NOT VALID;

-- video_categories
CREATE TABLE IF NOT EXISTS public.video_categories (
    video_id bigint NOT NULL,
    category_id bigint NOT NULL
);
ALTER TABLE public.video_categories ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.video_categories ADD COLUMN IF NOT EXISTS category_id bigint;

-- video_dubs
CREATE TABLE IF NOT EXISTS public.video_dubs (
    id bigint NOT NULL,
    audio_url character varying(1000) NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    duration_ms bigint NOT NULL,
    job_id bigint,
    language character varying(10) NOT NULL,
    mime_type character varying(50),
    size_bytes bigint NOT NULL,
    storage_key character varying(500) NOT NULL,
    transcript_id bigint,
    updated_at timestamp(6) without time zone,
    video_id bigint NOT NULL,
    voice character varying(80) NOT NULL
);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS audio_url character varying(1000);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS job_id bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS language character varying(10);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS mime_type character varying(50);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS size_bytes bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS storage_key character varying(500);
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS transcript_id bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.video_dubs ADD COLUMN IF NOT EXISTS voice character varying(80);

-- video_exports
CREATE TABLE IF NOT EXISTS public.video_exports (
    id bigint NOT NULL,
    audio_language character varying(10),
    created_at timestamp(6) without time zone,
    expires_at timestamp(6) without time zone NOT NULL,
    file_name character varying(300) NOT NULL,
    job_id bigint,
    size_bytes bigint NOT NULL,
    storage_key character varying(500) NOT NULL,
    subtitle_label character varying(100),
    url character varying(1000) NOT NULL,
    video_id bigint NOT NULL
);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS audio_language character varying(10);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS expires_at timestamp(6) without time zone;
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS file_name character varying(300);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS job_id bigint;
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS size_bytes bigint;
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS storage_key character varying(500);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS subtitle_label character varying(100);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS url character varying(1000);
ALTER TABLE public.video_exports ADD COLUMN IF NOT EXISTS video_id bigint;

-- video_tags
CREATE TABLE IF NOT EXISTS public.video_tags (
    video_id bigint NOT NULL,
    tag_id bigint NOT NULL
);
ALTER TABLE public.video_tags ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.video_tags ADD COLUMN IF NOT EXISTS tag_id bigint;

-- video_views
CREATE TABLE IF NOT EXISTS public.video_views (
    id bigint NOT NULL,
    completed boolean NOT NULL,
    session_id character varying(40),
    user_id bigint,
    video_id bigint NOT NULL,
    viewed_at timestamp(6) without time zone NOT NULL,
    watched_seconds integer NOT NULL
);
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS completed boolean;
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS session_id character varying(40);
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS viewed_at timestamp(6) without time zone;
ALTER TABLE public.video_views ADD COLUMN IF NOT EXISTS watched_seconds integer;

-- videos
CREATE TABLE IF NOT EXISTS public.videos (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    deleted_at timestamp(6) without time zone,
    description text,
    duration_seconds integer,
    enabled boolean NOT NULL,
    external_id character varying(64),
    file_size bigint,
    height integer,
    imported_from character varying(1000),
    language character varying(10),
    mime_type character varying(100),
    owner_id bigint,
    source character varying(20),
    source_author character varying(200),
    storage_key character varying(500),
    thumbnail_url character varying(1000),
    title character varying(200) NOT NULL,
    updated_at timestamp(6) without time zone,
    video_url character varying(1000) NOT NULL,
    width integer
);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS deleted_at timestamp(6) without time zone;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS duration_seconds integer;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS external_id character varying(64);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS file_size bigint;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS height integer;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS imported_from character varying(1000);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS language character varying(10);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS mime_type character varying(100);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS owner_id bigint;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS source character varying(20);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS source_author character varying(200);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS storage_key character varying(500);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS thumbnail_url character varying(1000);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS title character varying(200);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS video_url character varying(1000);
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS width integer;
ALTER TABLE public.videos DROP CONSTRAINT IF EXISTS videos_source_check;
ALTER TABLE public.videos ADD CONSTRAINT videos_source_check CHECK (((source)::text = ANY ((ARRAY['UPLOAD'::character varying, 'YOUTUBE'::character varying, 'VIMEO'::character varying, 'FACEBOOK'::character varying, 'URL'::character varying])::text[]))) NOT VALID;

-- watch_progress
CREATE TABLE IF NOT EXISTS public.watch_progress (
    id bigint NOT NULL,
    completed boolean NOT NULL,
    completed_at timestamp(6) without time zone,
    duration_seconds integer,
    last_watched_at timestamp(6) without time zone NOT NULL,
    position_seconds integer NOT NULL,
    user_id bigint NOT NULL,
    video_id bigint NOT NULL,
    watched_seconds bigint NOT NULL
);
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS completed boolean;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS completed_at timestamp(6) without time zone;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS duration_seconds integer;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS last_watched_at timestamp(6) without time zone;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS position_seconds integer;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS video_id bigint;
ALTER TABLE public.watch_progress ADD COLUMN IF NOT EXISTS watched_seconds bigint;

-- webhook_deliveries
CREATE TABLE IF NOT EXISTS public.webhook_deliveries (
    id bigint NOT NULL,
    attempt integer NOT NULL,
    created_at timestamp(6) without time zone,
    detail character varying(500),
    duration_ms bigint NOT NULL,
    event character varying(60) NOT NULL,
    message_id character varying(40) NOT NULL,
    payload text NOT NULL,
    status integer NOT NULL,
    success boolean NOT NULL,
    webhook_id bigint NOT NULL
);
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS attempt integer;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS detail character varying(500);
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS duration_ms bigint;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS event character varying(60);
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS message_id character varying(40);
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS payload text;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS status integer;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS success boolean;
ALTER TABLE public.webhook_deliveries ADD COLUMN IF NOT EXISTS webhook_id bigint;

-- webhooks
CREATE TABLE IF NOT EXISTS public.webhooks (
    id bigint NOT NULL,
    consecutive_failures integer NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    enabled boolean NOT NULL,
    events character varying(500) NOT NULL,
    last_delivery_at timestamp(6) without time zone,
    last_status integer,
    name character varying(100) NOT NULL,
    secret character varying(80) NOT NULL,
    updated_at timestamp(6) without time zone,
    url character varying(1000) NOT NULL
);
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS consecutive_failures integer;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS created_by character varying(100);
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS enabled boolean;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS events character varying(500);
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS last_delivery_at timestamp(6) without time zone;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS last_status integer;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS name character varying(100);
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS secret character varying(80);
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone;
ALTER TABLE public.webhooks ADD COLUMN IF NOT EXISTS url character varying(1000);

-- word_lookups
CREATE TABLE IF NOT EXISTS public.word_lookups (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    example character varying(300),
    from_language character varying(10) NOT NULL,
    meaning character varying(500),
    part_of_speech character varying(40),
    to_language character varying(10) NOT NULL,
    translation character varying(300) NOT NULL,
    word character varying(80) NOT NULL
);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS id bigint;
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS created_at timestamp(6) without time zone;
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS example character varying(300);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS from_language character varying(10);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS meaning character varying(500);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS part_of_speech character varying(40);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS to_language character varying(10);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS translation character varying(300);
ALTER TABLE public.word_lookups ADD COLUMN IF NOT EXISTS word character varying(80);

-- Identity (auto-increment) ids
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ai_chat_messages' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.ai_chat_messages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.ai_chat_messages_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ai_chats' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.ai_chats ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.ai_chats_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ai_generations' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.ai_generations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.ai_generations_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ai_usage' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.ai_usage ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.ai_usage_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'api_keys' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.api_keys ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.api_keys_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'audit_logs' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.audit_logs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.audit_logs_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'categories' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.categories ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.categories_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'collection_items' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.collection_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.collection_items_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'collections' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.collections ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.collections_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'content_revisions' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.content_revisions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.content_revisions_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'custom_roles' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.custom_roles ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.custom_roles_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'glossaries' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.glossaries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.glossaries_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'glossary_terms' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.glossary_terms ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.glossary_terms_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'languages' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.languages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.languages_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'notification_batches' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.notification_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.notification_batches_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'notification_events' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.notification_events ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.notification_events_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'notification_templates' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.notification_templates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.notification_templates_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'notifications' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.notifications ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.notifications_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'processing_job_logs' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.processing_job_logs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.processing_job_logs_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'processing_jobs' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.processing_jobs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.processing_jobs_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'quiz_attempts' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.quiz_attempts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.quiz_attempts_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'refresh_tokens' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.refresh_tokens ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.refresh_tokens_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'role_permissions' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.role_permissions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.role_permissions_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'study_cards' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.study_cards ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.study_cards_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'subtitle_comments' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.subtitle_comments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.subtitle_comments_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'subtitle_cues' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.subtitle_cues ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.subtitle_cues_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'subtitles' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.subtitles ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.subtitles_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'tags' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.tags ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.tags_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'transcript_segments' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.transcript_segments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.transcript_segments_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'transcripts' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.transcripts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.transcripts_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.users ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.users_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'video_dubs' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.video_dubs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.video_dubs_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'video_exports' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.video_exports ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.video_exports_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'video_views' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.video_views ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.video_views_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'videos' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.videos ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.videos_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'watch_progress' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.watch_progress ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.watch_progress_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'webhook_deliveries' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.webhook_deliveries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.webhook_deliveries_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'webhooks' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.webhooks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.webhooks_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'word_lookups' AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.word_lookups ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME public.word_lookups_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;

-- Keys and unique constraints
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_chat_messages_pkey' AND conrelid = 'public.ai_chat_messages'::regclass) THEN
        ALTER TABLE public.ai_chat_messages ADD CONSTRAINT ai_chat_messages_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_chats_pkey' AND conrelid = 'public.ai_chats'::regclass) THEN
        ALTER TABLE public.ai_chats ADD CONSTRAINT ai_chats_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_generations_pkey' AND conrelid = 'public.ai_generations'::regclass) THEN
        ALTER TABLE public.ai_generations ADD CONSTRAINT ai_generations_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_usage_pkey' AND conrelid = 'public.ai_usage'::regclass) THEN
        ALTER TABLE public.ai_usage ADD CONSTRAINT ai_usage_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'api_keys_pkey' AND conrelid = 'public.api_keys'::regclass) THEN
        ALTER TABLE public.api_keys ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'app_settings_pkey' AND conrelid = 'public.app_settings'::regclass) THEN
        ALTER TABLE public.app_settings ADD CONSTRAINT app_settings_pkey PRIMARY KEY (section);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'audit_logs_pkey' AND conrelid = 'public.audit_logs'::regclass) THEN
        ALTER TABLE public.audit_logs ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'categories_pkey' AND conrelid = 'public.categories'::regclass) THEN
        ALTER TABLE public.categories ADD CONSTRAINT categories_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'collection_items_pkey' AND conrelid = 'public.collection_items'::regclass) THEN
        ALTER TABLE public.collection_items ADD CONSTRAINT collection_items_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'collections_pkey' AND conrelid = 'public.collections'::regclass) THEN
        ALTER TABLE public.collections ADD CONSTRAINT collections_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'content_revisions_pkey' AND conrelid = 'public.content_revisions'::regclass) THEN
        ALTER TABLE public.content_revisions ADD CONSTRAINT content_revisions_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_roles_pkey' AND conrelid = 'public.custom_roles'::regclass) THEN
        ALTER TABLE public.custom_roles ADD CONSTRAINT custom_roles_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'glossaries_pkey' AND conrelid = 'public.glossaries'::regclass) THEN
        ALTER TABLE public.glossaries ADD CONSTRAINT glossaries_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'glossary_terms_pkey' AND conrelid = 'public.glossary_terms'::regclass) THEN
        ALTER TABLE public.glossary_terms ADD CONSTRAINT glossary_terms_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'languages_pkey' AND conrelid = 'public.languages'::regclass) THEN
        ALTER TABLE public.languages ADD CONSTRAINT languages_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notification_batches_pkey' AND conrelid = 'public.notification_batches'::regclass) THEN
        ALTER TABLE public.notification_batches ADD CONSTRAINT notification_batches_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notification_events_pkey' AND conrelid = 'public.notification_events'::regclass) THEN
        ALTER TABLE public.notification_events ADD CONSTRAINT notification_events_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notification_template_channels_pkey' AND conrelid = 'public.notification_template_channels'::regclass) THEN
        ALTER TABLE public.notification_template_channels ADD CONSTRAINT notification_template_channels_pkey PRIMARY KEY (template_id, channel);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notification_templates_pkey' AND conrelid = 'public.notification_templates'::regclass) THEN
        ALTER TABLE public.notification_templates ADD CONSTRAINT notification_templates_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notifications_pkey' AND conrelid = 'public.notifications'::regclass) THEN
        ALTER TABLE public.notifications ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'processing_job_logs_pkey' AND conrelid = 'public.processing_job_logs'::regclass) THEN
        ALTER TABLE public.processing_job_logs ADD CONSTRAINT processing_job_logs_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'processing_jobs_pkey' AND conrelid = 'public.processing_jobs'::regclass) THEN
        ALTER TABLE public.processing_jobs ADD CONSTRAINT processing_jobs_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'quiz_attempts_pkey' AND conrelid = 'public.quiz_attempts'::regclass) THEN
        ALTER TABLE public.quiz_attempts ADD CONSTRAINT quiz_attempts_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'refresh_tokens_pkey' AND conrelid = 'public.refresh_tokens'::regclass) THEN
        ALTER TABLE public.refresh_tokens ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'role_permissions_pkey' AND conrelid = 'public.role_permissions'::regclass) THEN
        ALTER TABLE public.role_permissions ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'study_cards_pkey' AND conrelid = 'public.study_cards'::regclass) THEN
        ALTER TABLE public.study_cards ADD CONSTRAINT study_cards_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subtitle_comments_pkey' AND conrelid = 'public.subtitle_comments'::regclass) THEN
        ALTER TABLE public.subtitle_comments ADD CONSTRAINT subtitle_comments_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subtitle_cues_pkey' AND conrelid = 'public.subtitle_cues'::regclass) THEN
        ALTER TABLE public.subtitle_cues ADD CONSTRAINT subtitle_cues_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'subtitles_pkey' AND conrelid = 'public.subtitles'::regclass) THEN
        ALTER TABLE public.subtitles ADD CONSTRAINT subtitles_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tags_pkey' AND conrelid = 'public.tags'::regclass) THEN
        ALTER TABLE public.tags ADD CONSTRAINT tags_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'transcript_segments_pkey' AND conrelid = 'public.transcript_segments'::regclass) THEN
        ALTER TABLE public.transcript_segments ADD CONSTRAINT transcript_segments_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'transcripts_pkey' AND conrelid = 'public.transcripts'::regclass) THEN
        ALTER TABLE public.transcripts ADD CONSTRAINT transcripts_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk590i2e6i9u4i77xek1ohkbwak' AND conrelid = 'public.notification_templates'::regclass) THEN
        ALTER TABLE public.notification_templates ADD CONSTRAINT uk590i2e6i9u4i77xek1ohkbwak UNIQUE (code);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk6dotkott2kjsp8vw4d0m25fb7' AND conrelid = 'public.users'::regclass) THEN
        ALTER TABLE public.users ADD CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_collection_items_collection_video' AND conrelid = 'public.collection_items'::regclass) THEN
        ALTER TABLE public.collection_items ADD CONSTRAINT uk_collection_items_collection_video UNIQUE (collection_id, video_id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_transcripts_video_language' AND conrelid = 'public.transcripts'::regclass) THEN
        ALTER TABLE public.transcripts ADD CONSTRAINT uk_transcripts_video_language UNIQUE (video_id, language);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_video_dubs_video_language' AND conrelid = 'public.video_dubs'::regclass) THEN
        ALTER TABLE public.video_dubs ADD CONSTRAINT uk_video_dubs_video_language UNIQUE (video_id, language);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_watch_progress_user_video' AND conrelid = 'public.watch_progress'::regclass) THEN
        ALTER TABLE public.watch_progress ADD CONSTRAINT uk_watch_progress_user_video UNIQUE (user_id, video_id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_word_lookups' AND conrelid = 'public.word_lookups'::regclass) THEN
        ALTER TABLE public.word_lookups ADD CONSTRAINT uk_word_lookups UNIQUE (word, from_language, to_language);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukcwrf6urvb9lnvuli1giq8t2k9' AND conrelid = 'public.api_keys'::regclass) THEN
        ALTER TABLE public.api_keys ADD CONSTRAINT ukcwrf6urvb9lnvuli1giq8t2k9 UNIQUE (key_hash);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukf5d7hxjge10f628bpt8444641' AND conrelid = 'public.collections'::regclass) THEN
        ALTER TABLE public.collections ADD CONSTRAINT ukf5d7hxjge10f628bpt8444641 UNIQUE (slug);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukfgqx307ychrch48smj55mk9hj' AND conrelid = 'public.custom_roles'::regclass) THEN
        ALTER TABLE public.custom_roles ADD CONSTRAINT ukfgqx307ychrch48smj55mk9hj UNIQUE (name);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukghpmfn23vmxfu3spu3lfg4r2d' AND conrelid = 'public.refresh_tokens'::regclass) THEN
        ALTER TABLE public.refresh_tokens ADD CONSTRAINT ukghpmfn23vmxfu3spu3lfg4r2d UNIQUE (token);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uki05sh3pii9a0gkilkbdh9xqey' AND conrelid = 'public.glossaries'::regclass) THEN
        ALTER TABLE public.glossaries ADD CONSTRAINT uki05sh3pii9a0gkilkbdh9xqey UNIQUE (name);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukmyc139vxcejowe4q8qm3ca5jn' AND conrelid = 'public.languages'::regclass) THEN
        ALTER TABLE public.languages ADD CONSTRAINT ukmyc139vxcejowe4q8qm3ca5jn UNIQUE (code);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukoul14ho7bctbefv8jywp5v3i2' AND conrelid = 'public.categories'::regclass) THEN
        ALTER TABLE public.categories ADD CONSTRAINT ukoul14ho7bctbefv8jywp5v3i2 UNIQUE (slug);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ukr43af9ap4edm43mmtq01oddj6' AND conrelid = 'public.users'::regclass) THEN
        ALTER TABLE public.users ADD CONSTRAINT ukr43af9ap4edm43mmtq01oddj6 UNIQUE (username);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uksn0d91hxu700qcw0n4pebp5vc' AND conrelid = 'public.tags'::regclass) THEN
        ALTER TABLE public.tags ADD CONSTRAINT uksn0d91hxu700qcw0n4pebp5vc UNIQUE (slug);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_pkey' AND conrelid = 'public.users'::regclass) THEN
        ALTER TABLE public.users ADD CONSTRAINT users_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_categories_pkey' AND conrelid = 'public.video_categories'::regclass) THEN
        ALTER TABLE public.video_categories ADD CONSTRAINT video_categories_pkey PRIMARY KEY (video_id, category_id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_dubs_pkey' AND conrelid = 'public.video_dubs'::regclass) THEN
        ALTER TABLE public.video_dubs ADD CONSTRAINT video_dubs_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_exports_pkey' AND conrelid = 'public.video_exports'::regclass) THEN
        ALTER TABLE public.video_exports ADD CONSTRAINT video_exports_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_tags_pkey' AND conrelid = 'public.video_tags'::regclass) THEN
        ALTER TABLE public.video_tags ADD CONSTRAINT video_tags_pkey PRIMARY KEY (video_id, tag_id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_views_pkey' AND conrelid = 'public.video_views'::regclass) THEN
        ALTER TABLE public.video_views ADD CONSTRAINT video_views_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'videos_pkey' AND conrelid = 'public.videos'::regclass) THEN
        ALTER TABLE public.videos ADD CONSTRAINT videos_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'watch_progress_pkey' AND conrelid = 'public.watch_progress'::regclass) THEN
        ALTER TABLE public.watch_progress ADD CONSTRAINT watch_progress_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'webhook_deliveries_pkey' AND conrelid = 'public.webhook_deliveries'::regclass) THEN
        ALTER TABLE public.webhook_deliveries ADD CONSTRAINT webhook_deliveries_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'webhooks_pkey' AND conrelid = 'public.webhooks'::regclass) THEN
        ALTER TABLE public.webhooks ADD CONSTRAINT webhooks_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'word_lookups_pkey' AND conrelid = 'public.word_lookups'::regclass) THEN
        ALTER TABLE public.word_lookups ADD CONSTRAINT word_lookups_pkey PRIMARY KEY (id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkhiswt16lqy567c45jgte16msb' AND conrelid = 'public.notification_template_channels'::regclass) THEN
        ALTER TABLE public.notification_template_channels ADD CONSTRAINT fkhiswt16lqy567c45jgte16msb FOREIGN KEY (template_id) REFERENCES public.notification_templates(id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkpr6ks7ia3ilx9ec2mmwb82lb6' AND conrelid = 'public.video_tags'::regclass) THEN
        ALTER TABLE public.video_tags ADD CONSTRAINT fkpr6ks7ia3ilx9ec2mmwb82lb6 FOREIGN KEY (video_id) REFERENCES public.videos(id);
    END IF;
END $$;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkqrdtbwe1eikatbh870575knpc' AND conrelid = 'public.video_categories'::regclass) THEN
        ALTER TABLE public.video_categories ADD CONSTRAINT fkqrdtbwe1eikatbh870575knpc FOREIGN KEY (video_id) REFERENCES public.videos(id);
    END IF;
END $$;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_ai_chat_messages_chat ON public.ai_chat_messages USING btree (chat_id, id);
CREATE INDEX IF NOT EXISTS idx_ai_chats_video ON public.ai_chats USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_ai_generations_video ON public.ai_generations USING btree (video_id, type, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON public.ai_usage USING btree (created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_video ON public.ai_usage USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON public.audit_logs USING btree (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_username ON public.audit_logs USING btree (username);
CREATE INDEX IF NOT EXISTS idx_collection_items_order ON public.collection_items USING btree (collection_id, "position");
CREATE INDEX IF NOT EXISTS idx_collection_items_video ON public.collection_items USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_collections_owner_id ON public.collections USING btree (owner_id);
CREATE INDEX IF NOT EXISTS idx_content_revisions_entity ON public.content_revisions USING btree (entity_type, entity_id, number);
CREATE INDEX IF NOT EXISTS idx_glossary_terms_glossary ON public.glossary_terms USING btree (glossary_id, "position");
CREATE INDEX IF NOT EXISTS idx_notification_events_notification ON public.notification_events USING btree (notification_id);
CREATE INDEX IF NOT EXISTS idx_notifications_batch ON public.notifications USING btree (batch_id);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON public.notifications USING btree (recipient_id, channel, read_at);
CREATE INDEX IF NOT EXISTS idx_processing_job_logs_job_id ON public.processing_job_logs USING btree (job_id, id);
CREATE INDEX IF NOT EXISTS idx_processing_jobs_status ON public.processing_jobs USING btree (status);
CREATE INDEX IF NOT EXISTS idx_processing_jobs_video_id ON public.processing_jobs USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_generation ON public.quiz_attempts USING btree (generation_id);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_user ON public.quiz_attempts USING btree (user_id, video_id);
CREATE INDEX IF NOT EXISTS idx_study_cards_user_due ON public.study_cards USING btree (user_id, due_at);
CREATE INDEX IF NOT EXISTS idx_study_cards_user_front ON public.study_cards USING btree (user_id, front_key);
CREATE INDEX IF NOT EXISTS idx_subtitle_comments_subtitle ON public.subtitle_comments USING btree (subtitle_id);
CREATE INDEX IF NOT EXISTS idx_subtitle_cues_subtitle ON public.subtitle_cues USING btree (subtitle_id, "position");
CREATE INDEX IF NOT EXISTS idx_subtitles_video_id ON public.subtitles USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_transcript_segments_transcript ON public.transcript_segments USING btree (transcript_id, "position");
CREATE INDEX IF NOT EXISTS idx_transcripts_video_id ON public.transcripts USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_video_dubs_video_id ON public.video_dubs USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_video_exports_video_id ON public.video_exports USING btree (video_id);
CREATE INDEX IF NOT EXISTS idx_video_views_user_id ON public.video_views USING btree (user_id);
CREATE INDEX IF NOT EXISTS idx_video_views_video_id ON public.video_views USING btree (video_id, viewed_at);
CREATE INDEX IF NOT EXISTS idx_videos_deleted_at ON public.videos USING btree (deleted_at);
CREATE INDEX IF NOT EXISTS idx_videos_owner_id ON public.videos USING btree (owner_id);
CREATE INDEX IF NOT EXISTS idx_watch_progress_user_recent ON public.watch_progress USING btree (user_id, last_watched_at);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_webhook ON public.webhook_deliveries USING btree (webhook_id, id);
