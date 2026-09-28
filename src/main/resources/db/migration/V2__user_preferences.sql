-- Per-user settings the apps remember across devices (subtitle languages,
-- caption style, timing offsets…), stored as one JSON document per user.
CREATE TABLE IF NOT EXISTS public.user_preferences (
    user_id bigint NOT NULL,
    data text NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT user_preferences_pkey PRIMARY KEY (user_id)
);
