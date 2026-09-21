-- patch_chunk.embedding 의 HNSW(cosine) 색인 — 백엔드 유사 사례 검색이 `<=>` 로 쓴다.
--
-- 운영 DB(서버1)에는 2026-09-21 15:27~15:50 에 첫 적재(176만 벡터) 뒤 손으로 만들었다:
--   CREATE INDEX CONCURRENTLY … WITH (m = 16, ef_construction = 64), 세션 maintenance_work_mem 6GB · 병렬 3.
--   색인 4,556MB. 색인 전에는 176만 벡터 순차 스캔으로 검색이 타임아웃 났다.
-- 여기서는 IF NOT EXISTS 로 같은 이름을 적어 스키마 기록만 맞춘다 — 운영에서는 no-op, 새 DB(테스트·로컬)에서는
-- 빈 표에 즉시 만들어진다. ⚠ 벡터가 이미 많은 표에 이 마이그레이션이 처음 도는 상황(색인 없는 새 운영 DB 에
-- 대량 적재 뒤)은 배포를 수십 분 막으니, 그때는 손으로 CONCURRENTLY 먼저 만들 것.
CREATE INDEX IF NOT EXISTS "idx_patch_chunk_embedding_hnsw"
    ON "patch_chunk" USING hnsw ("embedding" vector_cosine_ops) WITH (m = 16, ef_construction = 64);
