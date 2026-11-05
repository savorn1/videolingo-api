-- RuengFlix viewer accounts: sign in with a Cambodian phone number and a password. movie-api creates
-- and checks them (passwords hashed with scrypt there); the admin portal lists them and can disable one.
-- The phone number isn't verified yet (no SMS), so it identifies an account but doesn't prove ownership.
CREATE TABLE IF NOT EXISTS public.streaming_viewers (
    id bigserial PRIMARY KEY,
    -- E.164, e.g. +85512345678
    phone varchar(20) NOT NULL,
    display_name varchar(60) NOT NULL,
    password_hash varchar(200) NOT NULL,
    disabled boolean NOT NULL DEFAULT false,
    -- Raised to sign the viewer out everywhere (disabling does this); RuengFlix sessions carry the value they began with
    session_version integer NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL DEFAULT now(),
    last_login_at timestamp
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_streaming_viewers_phone ON public.streaming_viewers (phone);

-- A viewer's library (saved and followed series, watch history, unlocked series, votes, player settings)
-- as RuengFlix keeps it, so it follows them across devices. One JSON document per viewer.
CREATE TABLE IF NOT EXISTS public.streaming_viewer_libraries (
    viewer_id bigint PRIMARY KEY REFERENCES public.streaming_viewers (id) ON DELETE CASCADE,
    data_json text NOT NULL,
    updated_at timestamp NOT NULL DEFAULT now()
);
