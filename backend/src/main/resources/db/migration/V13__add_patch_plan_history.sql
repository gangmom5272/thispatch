-- 검색을 실행할 때마다 새로운 내역을 저장한다. 같은 원문에 UNIQUE를 두지 않는다.
-- 저장 정책: https://ssafy.atlassian.net/browse/S15P21A202-269
-- API 저장 연동은 별도 작업이며, 기존 테이블과 데이터는 변경하지 않는다.

CREATE TABLE "patch_plan" (
    "patch_plan_id" BIGSERIAL NOT NULL,
    "member_id" BIGINT NOT NULL,
    "appid" BIGINT NOT NULL,
    "raw_text" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_patch_plan" PRIMARY KEY ("patch_plan_id"),
    CONSTRAINT "fk_patch_plan_member" FOREIGN KEY ("member_id") REFERENCES "member" ("member_id"),
    CONSTRAINT "fk_patch_plan_game" FOREIGN KEY ("appid") REFERENCES "game" ("appid")
);

CREATE TABLE "patch_plan_genre" (
    "patch_plan_id" BIGINT NOT NULL,
    "genre_id" INTEGER NOT NULL,
    CONSTRAINT "pk_patch_plan_genre" PRIMARY KEY ("patch_plan_id", "genre_id"),
    CONSTRAINT "fk_patch_plan_genre_plan" FOREIGN KEY ("patch_plan_id") REFERENCES "patch_plan" ("patch_plan_id"),
    CONSTRAINT "fk_patch_plan_genre_tag" FOREIGN KEY ("genre_id") REFERENCES "tag" ("tag_id")
);

CREATE TABLE "patch_plan_entity" (
    "patch_plan_entity_id" BIGSERIAL NOT NULL,
    "patch_plan_id" BIGINT NOT NULL,
    "entity_order" INTEGER NOT NULL,
    "name" TEXT NOT NULL,
    "role" VARCHAR(30) NOT NULL,
    "warning_code" VARCHAR(30) NULL,
    "warning_message" TEXT NULL,
    CONSTRAINT "pk_patch_plan_entity" PRIMARY KEY ("patch_plan_entity_id"),
    CONSTRAINT "fk_patch_plan_entity_plan" FOREIGN KEY ("patch_plan_id") REFERENCES "patch_plan" ("patch_plan_id"),
    CONSTRAINT "uk_patch_plan_entity_order" UNIQUE ("patch_plan_id", "entity_order"),
    CONSTRAINT "ck_patch_plan_entity_order" CHECK ("entity_order" >= 1),
    CONSTRAINT "ck_patch_plan_entity_role" CHECK (
        "role" IN ('PLAYER', 'ENEMY', 'WEAPON', 'ITEM', 'SKILL', 'MAP', 'SYSTEM', 'OTHER', 'UNKNOWN')
    ),
    CONSTRAINT "ck_patch_plan_entity_warning" CHECK (
        ("warning_code" IS NULL AND "warning_message" IS NULL)
        OR ("warning_code" IS NOT NULL AND "warning_code" = 'UNKNOWN_ENTITY' AND "warning_message" IS NOT NULL)
    )
);

CREATE TABLE "patch_plan_slot" (
    "patch_plan_slot_id" BIGSERIAL NOT NULL,
    "patch_plan_entity_id" BIGINT NOT NULL,
    "slot_order" INTEGER NOT NULL,
    "attribute" TEXT NOT NULL,
    "change_type" VARCHAR(30) NOT NULL,
    "direction" VARCHAR(30) NOT NULL,
    "magnitude" TEXT NULL,
    "scope" TEXT NULL,
    CONSTRAINT "pk_patch_plan_slot" PRIMARY KEY ("patch_plan_slot_id"),
    CONSTRAINT "fk_patch_plan_slot_entity" FOREIGN KEY ("patch_plan_entity_id")
        REFERENCES "patch_plan_entity" ("patch_plan_entity_id"),
    CONSTRAINT "uk_patch_plan_slot_entity_order" UNIQUE ("patch_plan_entity_id", "slot_order"),
    CONSTRAINT "ck_patch_plan_slot_order" CHECK ("slot_order" >= 1),
    CONSTRAINT "ck_patch_plan_slot_change_type" CHECK (
        "change_type" IN ('ADD', 'REMOVE', 'MODIFY', 'FIX', 'DEPRECATE')
    ),
    CONSTRAINT "ck_patch_plan_slot_direction" CHECK (
        "direction" IN ('INCREASE', 'DECREASE', 'NONE', 'NOT_APPLICABLE', 'UNKNOWN')
    )
);

CREATE TABLE "patch_plan_restatement" (
    "patch_plan_id" BIGINT NOT NULL,
    "text" TEXT NOT NULL,
    "warning_code" VARCHAR(30) NULL,
    "warning_message" TEXT NULL,
    "created_at" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_patch_plan_restatement" PRIMARY KEY ("patch_plan_id"),
    CONSTRAINT "fk_patch_plan_restatement_plan" FOREIGN KEY ("patch_plan_id") REFERENCES "patch_plan" ("patch_plan_id"),
    CONSTRAINT "ck_patch_plan_restatement_warning" CHECK (
        ("warning_code" IS NULL AND "warning_message" IS NULL)
        OR ("warning_code" IS NOT NULL AND "warning_code" = 'NO_CHANGES' AND "warning_message" IS NOT NULL)
    )
);

CREATE TABLE "patch_plan_confirmed_slot" (
    "patch_plan_confirmed_slot_id" BIGSERIAL NOT NULL,
    "patch_plan_id" BIGINT NOT NULL,
    "slot_order" INTEGER NOT NULL,
    "target_name" TEXT NOT NULL,
    "target_role" VARCHAR(30) NOT NULL,
    "attribute" TEXT NOT NULL,
    "change_type" VARCHAR(30) NOT NULL,
    "direction" VARCHAR(30) NOT NULL,
    "magnitude" TEXT NULL,
    "scope" TEXT NULL,
    "created_at" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_patch_plan_confirmed_slot" PRIMARY KEY ("patch_plan_confirmed_slot_id"),
    CONSTRAINT "fk_patch_plan_confirmed_slot_plan" FOREIGN KEY ("patch_plan_id")
        REFERENCES "patch_plan" ("patch_plan_id"),
    CONSTRAINT "uk_patch_plan_confirmed_slot_order" UNIQUE ("patch_plan_id", "slot_order"),
    CONSTRAINT "ck_patch_plan_confirmed_slot_order" CHECK ("slot_order" >= 1),
    CONSTRAINT "ck_patch_plan_confirmed_slot_role" CHECK (
        "target_role" IN ('PLAYER', 'ENEMY', 'WEAPON', 'ITEM', 'SKILL', 'MAP', 'SYSTEM', 'OTHER', 'UNKNOWN')
    ),
    CONSTRAINT "ck_patch_plan_confirmed_slot_change_type" CHECK (
        "change_type" IN ('ADD', 'REMOVE', 'MODIFY', 'FIX', 'DEPRECATE')
    ),
    CONSTRAINT "ck_patch_plan_confirmed_slot_direction" CHECK (
        "direction" IN ('INCREASE', 'DECREASE', 'NONE', 'NOT_APPLICABLE', 'UNKNOWN')
    )
);

CREATE INDEX "idx_patch_plan_member_created_id"
    ON "patch_plan" ("member_id", "created_at" DESC, "patch_plan_id" DESC);

-- 하위 목록 조회는 각 PK/UNIQUE 인덱스의 선두 FK를 사용한다.
-- 최초 슬롯의 기획안 전체 순서와 엔티티(name, role) 중복 제거는 저장 로직이 보장한다.
-- 모든 FK는 기존 스키마처럼 NO ACTION이며 연쇄 삭제하지 않는다.

COMMENT ON TABLE "patch_plan" IS '검색 한 번의 패치 기획안 입력 내역';
COMMENT ON COLUMN "patch_plan"."patch_plan_id" IS '검색 내역 고유 ID. 재검색 시 새 ID를 생성한다.';
COMMENT ON COLUMN "patch_plan"."member_id" IS '기획안 작성자';
COMMENT ON COLUMN "patch_plan"."appid" IS '기획안 대상 게임. API의 gameId';
COMMENT ON COLUMN "patch_plan"."raw_text" IS '사용자가 입력한 원문 전체';
COMMENT ON COLUMN "patch_plan"."created_at" IS '검색 내역 저장 시각';

COMMENT ON TABLE "patch_plan_genre" IS '해당 검색에 사용자가 선택한 장르';
COMMENT ON COLUMN "patch_plan_genre"."patch_plan_id" IS '검색 내역 ID';
COMMENT ON COLUMN "patch_plan_genre"."genre_id" IS '선택한 장르. tag.tag_id 참조';

COMMENT ON TABLE "patch_plan_entity" IS '최초 구조화에서 탐지한 변경 대상과 대상별 경고';
COMMENT ON COLUMN "patch_plan_entity"."patch_plan_entity_id" IS '최초 엔티티 고유 ID';
COMMENT ON COLUMN "patch_plan_entity"."patch_plan_id" IS '검색 내역 ID';
COMMENT ON COLUMN "patch_plan_entity"."entity_order" IS '기획안 내 최초 엔티티 순서. 1부터 시작';
COMMENT ON COLUMN "patch_plan_entity"."name" IS '최초 탐지 대상 이름. 빈 문자열 허용';
COMMENT ON COLUMN "patch_plan_entity"."role" IS '최초 대상 역할';
COMMENT ON COLUMN "patch_plan_entity"."warning_code" IS '대상별 UNKNOWN_ENTITY 경고. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_entity"."warning_message" IS '당시 대상별 경고 문구. 없으면 NULL';

COMMENT ON TABLE "patch_plan_slot" IS '최초 엔티티에 연결된 변경 슬롯';
COMMENT ON COLUMN "patch_plan_slot"."patch_plan_slot_id" IS '최초 슬롯 고유 ID';
COMMENT ON COLUMN "patch_plan_slot"."patch_plan_entity_id" IS '최초 변경 대상 엔티티';
COMMENT ON COLUMN "patch_plan_slot"."slot_order" IS '기획안 전체 최초 슬롯 순서. 엔티티별 순서가 아님';
COMMENT ON COLUMN "patch_plan_slot"."attribute" IS '최초 변경 속성. 빈 문자열 허용';
COMMENT ON COLUMN "patch_plan_slot"."change_type" IS '최초 변경 유형';
COMMENT ON COLUMN "patch_plan_slot"."direction" IS '최초 변경 방향';
COMMENT ON COLUMN "patch_plan_slot"."magnitude" IS '최초 변화량 문자열. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_slot"."scope" IS '최초 적용 조건 및 범위. 없으면 NULL';

COMMENT ON TABLE "patch_plan_restatement" IS '최초 재진술 문장과 전체 구조화 경고';
COMMENT ON COLUMN "patch_plan_restatement"."patch_plan_id" IS '검색 내역 ID. 기획안당 최대 한 행';
COMMENT ON COLUMN "patch_plan_restatement"."text" IS '최초 restatement.text 전체. 빈 문자열 허용';
COMMENT ON COLUMN "patch_plan_restatement"."warning_code" IS '전체 NO_CHANGES 경고. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_restatement"."warning_message" IS '당시 전체 경고 문구. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_restatement"."created_at" IS '재진술 저장 시각';

COMMENT ON TABLE "patch_plan_confirmed_slot" IS '해당 검색에 제출한 최종 확정 슬롯';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."patch_plan_confirmed_slot_id" IS '최종 슬롯 고유 ID';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."patch_plan_id" IS '검색 내역 ID';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."slot_order" IS '최종 제출 슬롯 순서. 1부터 시작';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."target_name" IS '최종 대상 이름. 최초 대상의 수정 및 추가를 허용';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."target_role" IS '최종 대상 역할';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."attribute" IS '최종 변경 속성';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."change_type" IS '최종 변경 유형';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."direction" IS '최종 변경 방향';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."magnitude" IS '최종 변화량 문자열. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."scope" IS '최종 적용 조건 및 범위. 없으면 NULL';
COMMENT ON COLUMN "patch_plan_confirmed_slot"."created_at" IS '최종 슬롯 저장 시각';
