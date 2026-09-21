-- 구조화 시 최초 정보를 저장하고, 검색 성공 후 확정 정보를 저장한다.
-- 기존 V13 내역의 created_at은 검색 내역 저장 시각으로 그대로 보존한다.
-- 기존 내역의 실제 구조화 시각은 알 수 없으므로 structured_at을 역산하지 않는다.
ALTER TABLE "patch_plan"
    ADD COLUMN "structured_at" TIMESTAMPTZ NULL,
    ALTER COLUMN "created_at" DROP NOT NULL,
    ADD CONSTRAINT "ck_patch_plan_stored_at"
        CHECK ("structured_at" IS NOT NULL OR "created_at" IS NOT NULL);

COMMENT ON TABLE "patch_plan" IS '최초 구조화 정보와 검색 한 번의 확정 입력 내역. 검색 전에는 created_at이 NULL';
COMMENT ON COLUMN "patch_plan"."patch_plan_id" IS '구조화 시 생성한 기획안 ID. 첫 검색은 같은 ID를 확정하고 재검색은 새 ID에 원본 정보를 복사한다.';
COMMENT ON COLUMN "patch_plan"."structured_at" IS '최초 구조화 결과 저장 시각. 재검색 시 원본 값을 복사하며 기존 내역의 알 수 없는 시각은 NULL';
COMMENT ON COLUMN "patch_plan"."created_at" IS '검색 성공 후 확정 정보 저장 시각. NULL이면 미검색 기획안이며 마이페이지 내역에서 제외';

-- 단계별 하위 테이블 저장·원본 복사·확정 시각 기록은 저장 서비스가 한 트랜잭션으로 보장한다.
-- 기존 5개 하위 테이블·FK·인덱스는 유지하며 requestId나 별도 이력 테이블을 추가하지 않는다.
