-- RuengFlix viewers can also sign in with Telegram or Google (movie-api checks the sign-in and links it
-- here). An account has at least one way in: a phone number with a password, a Telegram account, or a
-- Google account. Telegram and Google ids are the providers' stable user ids, never reused.
ALTER TABLE public.streaming_viewers
    ALTER COLUMN phone DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL,
    -- Telegram user id; also the chat id the bot uses for this viewer's new-episode alerts
    ADD COLUMN IF NOT EXISTS telegram_id bigint,
    ADD COLUMN IF NOT EXISTS telegram_username varchar(64),
    -- Google's "sub" claim
    ADD COLUMN IF NOT EXISTS google_sub varchar(255),
    ADD COLUMN IF NOT EXISTS google_email varchar(320),
    ADD COLUMN IF NOT EXISTS photo_url varchar(1000);

CREATE UNIQUE INDEX IF NOT EXISTS uq_streaming_viewers_telegram ON public.streaming_viewers (telegram_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_streaming_viewers_google ON public.streaming_viewers (google_sub);

ALTER TABLE public.streaming_viewers DROP CONSTRAINT IF EXISTS streaming_viewers_sign_in;
ALTER TABLE public.streaming_viewers ADD CONSTRAINT streaming_viewers_sign_in CHECK (
    (phone IS NOT NULL AND password_hash IS NOT NULL) OR telegram_id IS NOT NULL OR google_sub IS NOT NULL
);
