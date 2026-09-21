-- patch_chunk · patch_change 적재기가 매일 gid 단위로 「지우고 다시 넣기」를 한다 (PatchChunkToPostgres ·
-- PatchChangeToPostgres). 인덱스가 없으면 176만 행(2026-09-21 첫 적재 규모)을 gid 마다 훑는다.
--   patch_chunk (gid, seq)    자연키. (gid, seq) → chunk_id 조회와 gid 단위 DELETE 가 쓴다
--   patch_change (chunk_id)   청크의 변경점 DELETE 와 화면 05 의 청크→변경점 조회가 쓴다
--
-- ⚠ 벡터(HNSW) 인덱스는 여기 넣지 않는다. 첫 적재 전에 만들어지면 176만 벡터를 하나씩 그래프에 끼워 넣어
--   적재가 몇 배 느려진다. 적재 뒤 서버에서 CREATE INDEX CONCURRENTLY 로 만들고, 그 다음 마이그레이션에
--   IF NOT EXISTS 로 같은 이름을 적어 스키마 기록만 맞춘다 (S15P21A202-257).
CREATE INDEX IF NOT EXISTS "idx_patch_chunk_gid_seq" ON "patch_chunk" ("gid", "seq");
CREATE INDEX IF NOT EXISTS "idx_patch_change_chunk_id" ON "patch_change" ("chunk_id");
