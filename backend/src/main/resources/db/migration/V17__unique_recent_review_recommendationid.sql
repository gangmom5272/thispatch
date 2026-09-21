-- recent_review.recommendationid 는 스팀 리뷰의 자연키다. 적재기가 리뷰당 최신본 하나만 넣으므로 실제로 유일했고
-- (2026-09-21 실측 1,097,318행 = distinct 1,097,318), 증분 적재(--incremental)의 upsert 가 이 유일성에 기댄다:
--   INSERT … ON CONFLICT (recommendationid) DO UPDATE … WHERE recent_review.updated_ts <= EXCLUDED.updated_ts
-- 운영에는 같은 날 손으로 만들었다(2초). IF NOT EXISTS 라 운영에서는 no-op.
CREATE UNIQUE INDEX IF NOT EXISTS "uq_recent_review_recommendationid" ON "recent_review" ("recommendationid");
