-- 서비스 표들의 조회 인덱스. 백엔드는 전부 appid 로 거르는데 표마다 대리키 PK 만 있어서
-- daily_stat(1,833만 행) 을 게임 하나 조회할 때마다 통째로 훑었다 — 반응 추세 페이지가 수 분씩 걸리고
-- recent_review DISTINCT ON 조회가 겹치면 서버1 부하가 8 을 넘었다 (2026-09-21 실측).
--
-- 운영 DB 에는 같은 날 CREATE INDEX CONCURRENTLY 로 먼저 만들었다(표를 잠그지 않으려고). 여기는 IF NOT EXISTS 로
-- 이름만 맞춰 스키마에 기록한다 — 운영에서는 no-op, 새 DB 에서는 빈 표에 즉시 만들어진다.
--
-- 적재기는 매일 TRUNCATE + INSERT 라 인덱스가 있어도 그대로 쓸 수 있다(TRUNCATE 는 인덱스를 비우기만 한다).
-- 삽입이 조금 느려지지만(daily_stat 1,800만 행 기준 수 분) 조회가 수 분 → 수 ms 로 바뀌는 것과 비교할 게 아니다.
CREATE INDEX IF NOT EXISTS "idx_recent_review_appid_latest" ON "recent_review" ("appid", "recommendationid", "updated_ts" DESC, "review_id" DESC);
CREATE INDEX IF NOT EXISTS "idx_daily_stat_appid_date"      ON "daily_stat" ("appid", "stat_date");
CREATE INDEX IF NOT EXISTS "idx_patch_stat_appid_patched"   ON "patch_stat" ("appid", "patched_at");
CREATE INDEX IF NOT EXISTS "idx_band_stat_appid"            ON "band_stat" ("appid", "band_no");
CREATE INDEX IF NOT EXISTS "idx_band_topic_stat_band"       ON "band_topic_stat" ("band_stat_id");
CREATE INDEX IF NOT EXISTS "idx_language_stat_appid"        ON "language_stat" ("appid");
CREATE INDEX IF NOT EXISTS "idx_news_appid_published"       ON "news" ("appid", "published_ts");
