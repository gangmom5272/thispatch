ALTER TABLE "member"
    ADD COLUMN "refresh_token_hash" VARCHAR(64) NULL,
    ADD COLUMN "refresh_token_expires_at" TIMESTAMPTZ NULL;
