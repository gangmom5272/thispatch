-- news.published_at -> news.published_ts
--
-- 우리 규약은 이렇다.
--
--   _ts   스팀이 준 값       created_ts · updated_ts · release_ts
--   _at   우리가 만든 값     collected_at · aggregated_at · processed_at · patched_at
--
-- 실제 DB 의 시각 컬럼 20개 중 19개가 이 규칙을 따르는데 news.published_at
-- 하나만 예외였다. 스팀이 주는 공지 게시일인데 _at 으로 되어 있었다.
--
-- 같은 테이블 안에서도 구분이 안 됐다.
--
--   published_at   스팀이 준 것
--   collected_at   우리가 만든 것
--
-- 이름이 곧 역할 분담이기도 하다 (2026-09-15 데이터·AI 담당 합의).
--
--   published_ts   스팀이 준 게시일        수집 쪽이 채운다
--   patched_at     본문에서 뽑은 적용일     판정 쪽(S15P21A202-130)이 채운다
--
-- 지금 news 는 0행이고 아무도 읽지 않는다. 고치려면 지금이 제일 싸다.
--
-- ⚠ 멱등하게 쓴다. 사람이 손으로 먼저 바꿔 둔 DB 에서도 Flyway 가 터지지
--   않아야 한다. 그냥 ALTER 로 두면 이미 바뀐 DB 에서 백엔드가 아예 못 뜬다.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'news'
          AND column_name = 'published_at'
    ) THEN
        ALTER TABLE "news" RENAME COLUMN "published_at" TO "published_ts";
    END IF;
END
$$;
