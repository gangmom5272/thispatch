CREATE TABLE "patch_change_type" (
    "change_type_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "sort_order" SMALLINT NOT NULL,
    "is_active" BOOLEAN NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_change_direction" (
    "direction_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "sort_order" SMALLINT NOT NULL,
    "is_active" BOOLEAN NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_change_target_type" (
    "target_type_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "sort_order" SMALLINT NOT NULL,
    "is_active" BOOLEAN NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_chunk" (
    "chunk_id" BIGSERIAL NOT NULL,
    "gid" VARCHAR(20) NOT NULL,
    "seq" SMALLINT NOT NULL,
    "heading_path" VARCHAR(300) NULL,
    "text" TEXT NOT NULL,
    "extraction_status" VARCHAR(10) NOT NULL,
    "embedding_status" VARCHAR(10) NOT NULL,
    "embedding" vector(768) NULL,
    "embedding_model" VARCHAR(50) NULL,
    "extraction_run_id" BIGINT NULL,
    "model_version" VARCHAR(50) NULL,
    "prompt_version" VARCHAR(30) NULL,
    "processed_at" TIMESTAMPTZ NULL
);

CREATE TABLE "patch_change" (
    "patch_change_id" BIGSERIAL NOT NULL,
    "chunk_id" BIGINT NOT NULL,
    "seq" SMALLINT NOT NULL,
    "change_type_id" SMALLINT NULL,
    "direction_id" SMALLINT NULL,
    "target_type_id" SMALLINT NULL,
    "change_text" TEXT NULL,
    "target" VARCHAR(200) NULL,
    "attribute" VARCHAR(50) NULL,
    "value_text" TEXT NULL,
    "value_before" NUMERIC NULL,
    "value_after" NUMERIC NULL,
    "unit" VARCHAR(20) NULL,
    "conditions" JSONB NULL,
    "evidence_quote" TEXT NULL,
    "validation_status" VARCHAR(12) NULL,
    "validation_errors" JSONB NULL,
    "confidence" NUMERIC NULL
);

-- Primary Keys
ALTER TABLE "patch_change_type"
    ADD CONSTRAINT "pk_patch_change_type"
        PRIMARY KEY ("change_type_id");

ALTER TABLE "patch_change_direction"
    ADD CONSTRAINT "pk_patch_change_direction"
        PRIMARY KEY ("direction_id");

ALTER TABLE "patch_change_target_type"
    ADD CONSTRAINT "pk_patch_change_target_type"
        PRIMARY KEY ("target_type_id");

ALTER TABLE "patch_chunk"
    ADD CONSTRAINT "pk_patch_chunk"
        PRIMARY KEY ("chunk_id");

ALTER TABLE "patch_change"
    ADD CONSTRAINT "pk_patch_change"
        PRIMARY KEY ("patch_change_id");

-- Unique Constraints
ALTER TABLE "patch_change_type"
    ADD CONSTRAINT "uq_patch_change_type_code"
        UNIQUE ("code");

ALTER TABLE "patch_change_direction"
    ADD CONSTRAINT "uq_patch_change_direction_code"
        UNIQUE ("code");

ALTER TABLE "patch_change_target_type"
    ADD CONSTRAINT "uq_patch_change_target_type_code"
        UNIQUE ("code");

ALTER TABLE "patch_chunk"
    ADD CONSTRAINT "uq_patch_chunk_gid_seq"
        UNIQUE ("gid", "seq");

ALTER TABLE "patch_change"
    ADD CONSTRAINT "uq_patch_change_chunk_seq"
        UNIQUE ("chunk_id", "seq");

-- Foreign Keys
ALTER TABLE "patch_chunk"
    ADD CONSTRAINT "fk_patch_chunk_news"
        FOREIGN KEY ("gid") REFERENCES "news" ("gid"),
    ADD CONSTRAINT "fk_patch_chunk_extraction_run"
        FOREIGN KEY ("extraction_run_id") REFERENCES "batch_job" ("batch_job_id");

ALTER TABLE "patch_change"
    ADD CONSTRAINT "fk_patch_change_chunk"
        FOREIGN KEY ("chunk_id") REFERENCES "patch_chunk" ("chunk_id"),
    ADD CONSTRAINT "fk_patch_change_change_type"
        FOREIGN KEY ("change_type_id") REFERENCES "patch_change_type" ("change_type_id"),
    ADD CONSTRAINT "fk_patch_change_direction"
        FOREIGN KEY ("direction_id") REFERENCES "patch_change_direction" ("direction_id"),
    ADD CONSTRAINT "fk_patch_change_target_type"
        FOREIGN KEY ("target_type_id") REFERENCES "patch_change_target_type" ("target_type_id");

-- Check Constraints
ALTER TABLE "patch_chunk"
    ADD CONSTRAINT "ck_patch_chunk_extraction_status"
        CHECK ("extraction_status" IN ('pending', 'running', 'succeeded', 'failed')),
    ADD CONSTRAINT "ck_patch_chunk_embedding_status"
        CHECK ("embedding_status" IN ('pending', 'succeeded', 'failed'));

ALTER TABLE "patch_change"
    ADD CONSTRAINT "ck_patch_change_validation_status"
        CHECK (
            "validation_status" IS NULL
            OR "validation_status" IN ('valid', 'needs_review', 'rejected')
        ),
    ADD CONSTRAINT "ck_patch_change_confidence"
        CHECK (
            "confidence" IS NULL
            OR ("confidence" >= 0 AND "confidence" <= 1)
        );

-- Seed Data
INSERT INTO "patch_change_type"
    ("code", "name_ko", "sort_order", "is_active", "definition")
VALUES
    ('add',       '추가',      1, TRUE, NULL),
    ('remove',    '제거',      2, TRUE, NULL),
    ('modify',    '변경',      3, TRUE, NULL),
    ('fix',       '수정',      4, TRUE, NULL),
    ('deprecate', '지원 중단', 5, TRUE, NULL);

INSERT INTO "patch_change_direction"
    ("code", "name_ko", "sort_order", "is_active", "definition")
VALUES
    ('increase',       '증가',       1, TRUE, NULL),
    ('decrease',       '감소',       2, TRUE, NULL),
    ('none',           '방향 없음',  3, TRUE, NULL),
    ('not_applicable', '해당 없음',  4, TRUE, NULL),
    ('unknown',        '알 수 없음', 5, TRUE, NULL);

INSERT INTO "patch_change_target_type"
    ("code", "name_ko", "sort_order", "is_active", "definition")
VALUES
    ('player',  '플레이어',   1, TRUE, NULL),
    ('enemy',   '적',         2, TRUE, NULL),
    ('weapon',  '무기',       3, TRUE, NULL),
    ('item',    '아이템',     4, TRUE, NULL),
    ('skill',   '스킬',       5, TRUE, NULL),
    ('map',     '맵',         6, TRUE, NULL),
    ('system',  '시스템',     7, TRUE, NULL),
    ('other',   '기타',       8, TRUE, NULL),
    ('unknown', '알 수 없음', 9, TRUE, NULL);

-- Indexes
CREATE INDEX "idx_patch_chunk_gid"
    ON "patch_chunk" ("gid");

CREATE INDEX "idx_patch_chunk_embedding_hnsw"
    ON "patch_chunk"
    USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding_status" = 'succeeded';

CREATE INDEX "idx_patch_change_direction_change_type"
    ON "patch_change" ("direction_id", "change_type_id");
