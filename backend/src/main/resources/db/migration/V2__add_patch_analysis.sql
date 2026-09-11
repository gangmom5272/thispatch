CREATE TABLE "patch_change_type" (
    "change_type_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_change_direction" (
    "direction_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_change_target_type" (
    "target_type_id" SMALLSERIAL NOT NULL,
    "code" VARCHAR(30) NOT NULL,
    "name_ko" VARCHAR(50) NOT NULL,
    "definition" TEXT NULL
);

CREATE TABLE "patch_chunk" (
    "chunk_id" BIGSERIAL NOT NULL,
    "gid" VARCHAR(20) NOT NULL,
    "seq" SMALLINT NOT NULL,
    "text" TEXT NOT NULL,
    "extraction_status" VARCHAR(10) NOT NULL,
    "embedding_status" VARCHAR(10) NOT NULL,
    "embedding" vector(512) NULL,
    "embedding_model" VARCHAR(50) NULL,
    "model_version" VARCHAR(50) NULL,
    "processed_at" TIMESTAMPTZ NULL
);

CREATE TABLE "patch_change" (
    "patch_change_id" BIGSERIAL NOT NULL,
    "chunk_id" BIGINT NOT NULL,
    "change_type_id" SMALLINT NULL,
    "direction_id" SMALLINT NULL,
    "target_type_id" SMALLINT NULL,
    "evidence_quote" TEXT NULL,
    "validation_status" VARCHAR(12) NULL
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

-- Foreign Keys
ALTER TABLE "patch_chunk"
    ADD CONSTRAINT "fk_patch_chunk_news"
        FOREIGN KEY ("gid")
        REFERENCES "news" ("gid");

ALTER TABLE "patch_change"
    ADD CONSTRAINT "fk_patch_change_chunk"
        FOREIGN KEY ("chunk_id")
        REFERENCES "patch_chunk" ("chunk_id"),
    ADD CONSTRAINT "fk_patch_change_change_type"
        FOREIGN KEY ("change_type_id")
        REFERENCES "patch_change_type" ("change_type_id"),
    ADD CONSTRAINT "fk_patch_change_direction"
        FOREIGN KEY ("direction_id")
        REFERENCES "patch_change_direction" ("direction_id"),
    ADD CONSTRAINT "fk_patch_change_target_type"
        FOREIGN KEY ("target_type_id")
        REFERENCES "patch_change_target_type" ("target_type_id");

-- Seed Data
INSERT INTO "patch_change_type"
    ("code", "name_ko", "definition")
VALUES
    ('add', '추가', NULL),
    ('remove', '제거', NULL),
    ('modify', '변경', NULL),
    ('fix', '수정', NULL),
    ('deprecate', '지원 중단', NULL);

INSERT INTO "patch_change_direction"
    ("code", "name_ko", "definition")
VALUES
    ('increase', '증가', NULL),
    ('decrease', '감소', NULL),
    ('none', '방향 없음', NULL),
    ('not_applicable', '해당 없음', NULL),
    ('unknown', '알 수 없음', NULL);

INSERT INTO "patch_change_target_type"
    ("code", "name_ko", "definition")
VALUES
    ('player', '플레이어', NULL),
    ('enemy', '적', NULL),
    ('weapon', '무기', NULL),
    ('item', '아이템', NULL),
    ('skill', '스킬', NULL),
    ('map', '맵', NULL),
    ('system', '시스템', NULL),
    ('other', '기타', NULL),
    ('unknown', '알 수 없음', NULL);
