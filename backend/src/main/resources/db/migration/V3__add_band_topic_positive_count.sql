-- Historical rows contain only negative counts. Their positive counts are unknown, not zero.
-- Recompute each game's bands and topic counts together before displaying the new rates.
ALTER TABLE "band_topic_stat"
    ADD COLUMN "positive_count" INTEGER NULL;

ALTER TABLE "band_topic_stat"
    ADD CONSTRAINT "ck_band_topic_stat_positive_count"
        CHECK ("positive_count" >= 0);

COMMENT ON COLUMN "band_topic_stat"."positive_count" IS
    'Number of recommended reviews mentioning this topic in the band; NULL means historical data not yet recomputed. Not topic-level sentiment.';

-- New aggregation writes explicit counts, including zero. After all historical rows are
-- recomputed, a later migration can require NOT NULL. Do not backfill unknown counts with 0.
