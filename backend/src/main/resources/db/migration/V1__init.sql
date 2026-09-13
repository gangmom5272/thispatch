CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "patch_stat" (
    "gid" VARCHAR(20) NOT NULL,
    "appid" BIGINT NOT NULL,
    "patched_at" TIMESTAMPTZ NOT NULL,
    "before_review_count" INTEGER NOT NULL,
    "before_positive_pct" NUMERIC(5,2) NULL,
    "after_review_count" INTEGER NOT NULL,
    "after_positive_pct" NUMERIC(5,2) NULL,
    "delta_pct" NUMERIC(5,2) NULL,
    "stat_date" DATE NULL,
    "aggregated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "tag" (
    "tag_id" INTEGER NOT NULL,
    "name_ko" VARCHAR(100) NOT NULL,
    "collected_at" TIMESTAMPTZ NULL
);

CREATE TABLE "my_game" (
    "member_id" BIGINT NOT NULL,
    "appid" BIGINT NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL
);

CREATE TABLE "daily_stat" (
    "daily_stat_id" BIGSERIAL NOT NULL,
    "appid" BIGINT NOT NULL,
    "review_count" INTEGER NOT NULL,
    "negative_count" INTEGER NULL,
    "new_review_count" INTEGER NOT NULL,
    "new_positive_count" INTEGER NOT NULL,
    "edited_review_count" INTEGER NOT NULL,
    "edited_positive_count" INTEGER NOT NULL,
    "stat_date" DATE NOT NULL,
    "aggregated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "topic" (
    "topic_id" SMALLINT NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "centroid" vector(768) NULL,
    "sim_threshold" NUMERIC(4,3) NOT NULL
);

CREATE TABLE "news" (
    "gid" VARCHAR(20) NOT NULL,
    "appid" BIGINT NOT NULL,
    "title" VARCHAR(500) NOT NULL,
    "contents" TEXT NOT NULL,
    "url" VARCHAR(1000) NULL,
    "published_at" TIMESTAMPTZ NOT NULL,
    "is_developer_post" BOOLEAN NULL,
    "feed_tags" VARCHAR(300) NULL,
    "is_patch" BOOLEAN NOT NULL,
    "patch_reason" TEXT NULL,
    "collected_at" TIMESTAMPTZ NOT NULL
);

CREATE TABLE "band_topic_stat" (
    "band_topic_stat_id" BIGSERIAL NOT NULL,
    "band_stat_id" BIGINT NOT NULL,
    "topic_id" SMALLINT NOT NULL,
    "negative_count" INTEGER NOT NULL
);

CREATE TABLE "band_stat" (
    "band_stat_id" BIGSERIAL NOT NULL,
    "appid" BIGINT NOT NULL,
    "band_no" SMALLINT NOT NULL,
    "playtime_from" INTEGER NOT NULL,
    "playtime_to" INTEGER NULL,
    "review_count" INTEGER NOT NULL,
    "positive_count" INTEGER NOT NULL,
    "aggregated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "recent_review" (
    "review_id" BIGSERIAL NOT NULL,
    "recommendationid" BIGINT NOT NULL,
    "appid" BIGINT NOT NULL,
    "review_text" TEXT NOT NULL,
    "voted_up" BOOLEAN NOT NULL,
    "votes_up" INTEGER NOT NULL,
    "playtime_at_review" INTEGER NULL,
    "language_code" VARCHAR(20) NOT NULL,
    "band_no" SMALLINT NULL,
    "created_ts" TIMESTAMPTZ NOT NULL,
    "updated_ts" TIMESTAMPTZ NOT NULL
);

CREATE TABLE "batch_log" (
    "batch_log_id" BIGSERIAL NOT NULL,
    "batch_job_id" INTEGER NOT NULL,
    "started_at" TIMESTAMPTZ NOT NULL,
    "finished_at" TIMESTAMPTZ NULL,
    "status" VARCHAR(10) NOT NULL,
    "input_count" INTEGER NULL,
    "output_count" INTEGER NULL,
    "error_count" INTEGER NULL,
    "error_summary" TEXT NULL
);

CREATE TABLE "game_tag" (
    "appid" BIGINT NOT NULL,
    "tag_id" INTEGER NOT NULL,
    "weight" INTEGER NOT NULL
);

CREATE TABLE "game" (
    "appid" BIGINT NOT NULL,
    "name" VARCHAR(500) NOT NULL,
    "developer" VARCHAR(500) NULL,
    "publisher" VARCHAR(500) NULL,
    "short_description" TEXT NULL,
    "store_url_path" VARCHAR(300) NULL,
    "release_ts" TIMESTAMPTZ NULL,
    "is_early_access" BOOLEAN NULL,
    "is_coming_soon" BOOLEAN NULL,
    "store_review_count" INTEGER NULL,
    "store_positive_pct" SMALLINT NULL,
    "capsule_path" VARCHAR(200) NULL,
    "collected_at" TIMESTAMPTZ NOT NULL
);

CREATE TABLE "language" (
    "language_code" VARCHAR(20) NOT NULL,
    "name_ko" VARCHAR(20) NOT NULL
);

CREATE TABLE "language_stat" (
    "language_stat_id" BIGSERIAL NOT NULL,
    "appid" BIGINT NOT NULL,
    "language_code" VARCHAR(20) NOT NULL,
    "review_count" INTEGER NOT NULL,
    "positive_count" INTEGER NOT NULL,
    "aggregated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "member" (
    "member_id" BIGSERIAL NOT NULL,
    "login_type" VARCHAR(10) NOT NULL,
    "email" VARCHAR(255) NULL,
    "password" CHAR(64) NULL,
    "steam_id" NUMERIC(20, 0) NULL,
    "nickname" VARCHAR(50) NULL,
    "status" VARCHAR(10) NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL,
    "updated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "batch_job" (
    "batch_job_id" INTEGER NOT NULL,
    "job_name" VARCHAR(50) NOT NULL,
    "description" TEXT NULL,
    "is_active" BOOLEAN NOT NULL,
    "created_at" TIMESTAMPTZ NULL,
    "updated_at" TIMESTAMPTZ NULL
);

CREATE TABLE "review_topic" (
    "review_id" BIGINT NOT NULL,
    "topic_id" SMALLINT NOT NULL
);

-- Primary Keys
ALTER TABLE "patch_stat"
    ADD CONSTRAINT "pk_patch_stat" PRIMARY KEY ("gid");

ALTER TABLE "tag"
    ADD CONSTRAINT "pk_tag" PRIMARY KEY ("tag_id");

ALTER TABLE "my_game"
    ADD CONSTRAINT "pk_my_game" PRIMARY KEY ("member_id", "appid");

ALTER TABLE "daily_stat"
    ADD CONSTRAINT "pk_daily_stat" PRIMARY KEY ("daily_stat_id");

ALTER TABLE "topic"
    ADD CONSTRAINT "pk_topic" PRIMARY KEY ("topic_id");

ALTER TABLE "news"
    ADD CONSTRAINT "pk_news" PRIMARY KEY ("gid");

ALTER TABLE "band_topic_stat"
    ADD CONSTRAINT "pk_band_topic_stat" PRIMARY KEY ("band_topic_stat_id");

ALTER TABLE "band_stat"
    ADD CONSTRAINT "pk_band_stat" PRIMARY KEY ("band_stat_id");

ALTER TABLE "recent_review"
    ADD CONSTRAINT "pk_recent_review" PRIMARY KEY ("review_id");

ALTER TABLE "batch_log"
    ADD CONSTRAINT "pk_batch_log" PRIMARY KEY ("batch_log_id");

ALTER TABLE "game_tag"
    ADD CONSTRAINT "pk_game_tag" PRIMARY KEY ("appid", "tag_id");

ALTER TABLE "game"
    ADD CONSTRAINT "pk_game" PRIMARY KEY ("appid");

ALTER TABLE "language"
    ADD CONSTRAINT "pk_language" PRIMARY KEY ("language_code");

ALTER TABLE "language_stat"
    ADD CONSTRAINT "pk_language_stat" PRIMARY KEY ("language_stat_id");

ALTER TABLE "member"
    ADD CONSTRAINT "pk_member" PRIMARY KEY ("member_id");

ALTER TABLE "batch_job"
    ADD CONSTRAINT "pk_batch_job" PRIMARY KEY ("batch_job_id");

ALTER TABLE "review_topic"
    ADD CONSTRAINT "pk_review_topic" PRIMARY KEY ("review_id", "topic_id");

-- Foreign Keys
ALTER TABLE "my_game"
    ADD CONSTRAINT "fk_my_game_member"
        FOREIGN KEY ("member_id") REFERENCES "member" ("member_id"),
    ADD CONSTRAINT "fk_my_game_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid");

ALTER TABLE "news"
    ADD CONSTRAINT "fk_news_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid");

ALTER TABLE "daily_stat"
    ADD CONSTRAINT "fk_daily_stat_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid");

ALTER TABLE "patch_stat"
    ADD CONSTRAINT "fk_patch_stat_news"
        FOREIGN KEY ("gid") REFERENCES "news" ("gid"),
    ADD CONSTRAINT "fk_patch_stat_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid");

ALTER TABLE "band_stat"
    ADD CONSTRAINT "fk_band_stat_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid");

ALTER TABLE "band_topic_stat"
    ADD CONSTRAINT "fk_band_topic_stat_band_stat"
        FOREIGN KEY ("band_stat_id") REFERENCES "band_stat" ("band_stat_id"),
    ADD CONSTRAINT "fk_band_topic_stat_topic"
        FOREIGN KEY ("topic_id") REFERENCES "topic" ("topic_id");

ALTER TABLE "recent_review"
    ADD CONSTRAINT "fk_recent_review_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid"),
    ADD CONSTRAINT "fk_recent_review_language"
        FOREIGN KEY ("language_code") REFERENCES "language" ("language_code");

ALTER TABLE "game_tag"
    ADD CONSTRAINT "fk_game_tag_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid"),
    ADD CONSTRAINT "fk_game_tag_tag"
        FOREIGN KEY ("tag_id") REFERENCES "tag" ("tag_id");

ALTER TABLE "language_stat"
    ADD CONSTRAINT "fk_language_stat_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid"),
    ADD CONSTRAINT "fk_language_stat_language"
        FOREIGN KEY ("language_code") REFERENCES "language" ("language_code");

ALTER TABLE "batch_log"
    ADD CONSTRAINT "fk_batch_log_batch_job"
        FOREIGN KEY ("batch_job_id") REFERENCES "batch_job" ("batch_job_id");

ALTER TABLE "review_topic"
    ADD CONSTRAINT "fk_review_topic_recent_review"
        FOREIGN KEY ("review_id") REFERENCES "recent_review" ("review_id"),
    ADD CONSTRAINT "fk_review_topic_topic"
        FOREIGN KEY ("topic_id") REFERENCES "topic" ("topic_id");
