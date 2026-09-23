-- 같은 슬롯 벡터·모델·장르·DB 스냅샷으로 LIMIT 전후 필터를 비교한다.
-- 필수 psql 변수 slots: [{"change_type":"modify","direction":"decrease","embedding":[...]}]
-- 실행 방법과 결과 해석: compare_case_search.md
\set ON_ERROR_STOP on
\timing on
\if :{?model}
\else
\set model embeddinggemma-300m-bf16-512
\endif
\if :{?genre_ids}
\else
\set genre_ids '{}'
\endif
\if :{?ef_search}
\else
\set ef_search 100
\endif

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL hnsw.iterative_scan = 'strict_order';
SELECT set_config('hnsw.ef_search', :'ef_search', true);

WITH slots AS MATERIALIZED (
    SELECT slot_index, lower(slot->>'change_type') AS change_type,
           lower(slot->>'direction') AS direction, (slot->'embedding')::text::vector(512) AS embedding
    FROM jsonb_array_elements(:'slots'::jsonb) WITH ORDINALITY AS input(slot, slot_index)
), before_candidates AS MATERIALIZED (
    SELECT q.slot_index, candidate.*
    FROM slots q CROSS JOIN LATERAL (
        SELECT c.chunk_id, c.gid, c.embedding <=> q.embedding AS distance
        FROM patch_chunk c
        WHERE c.embedding_status = 'succeeded'
          AND c.embedding IS NOT NULL AND c.embedding_model = :'model'
          AND EXISTS (
              SELECT 1 FROM news n JOIN patch_stat s ON s.gid = n.gid AND s.appid = n.appid
              WHERE n.gid = c.gid AND n.is_patch = true
                AND s.before_review_count > 0 AND s.after_review_count > 0
                AND s.before_positive_pct IS NOT NULL AND s.after_positive_pct IS NOT NULL
                AND (cardinality(:'genre_ids'::integer[]) = 0 OR EXISTS (
                    SELECT 1 FROM game_tag gt WHERE gt.appid = n.appid AND gt.tag_id = ANY(:'genre_ids'::integer[])
                ))
              OFFSET 0
          )
        ORDER BY c.embedding <=> q.embedding LIMIT 30
    ) candidate
), before_matches AS MATERIALIZED (
    SELECT candidate.* FROM before_candidates candidate JOIN slots q USING (slot_index)
    WHERE EXISTS (
        SELECT 1 FROM patch_change pc
        JOIN patch_change_type ct ON ct.change_type_id = pc.change_type_id
        JOIN patch_change_direction d ON d.direction_id = pc.direction_id
        WHERE pc.chunk_id = candidate.chunk_id AND ct.code = q.change_type
          AND (q.change_type <> 'modify' OR d.code = q.direction)
          AND pc.validation_status IN ('valid', 'needs_review')
    )
), after_matches AS MATERIALIZED (
    SELECT q.slot_index, candidate.*
    FROM slots q CROSS JOIN LATERAL (
        SELECT c.chunk_id, c.gid, c.embedding <=> q.embedding AS distance
        FROM patch_chunk c
        WHERE c.embedding_status = 'succeeded'
          AND c.embedding IS NOT NULL AND c.embedding_model = :'model'
          AND EXISTS (
              SELECT 1 FROM patch_change pc
              JOIN patch_change_type ct ON ct.change_type_id = pc.change_type_id
              JOIN patch_change_direction d ON d.direction_id = pc.direction_id
              WHERE pc.chunk_id = c.chunk_id AND ct.code = q.change_type
                AND (q.change_type <> 'modify' OR d.code = q.direction)
                AND pc.validation_status IN ('valid', 'needs_review')
              OFFSET 0
          )
          AND EXISTS (
              SELECT 1 FROM news n JOIN patch_stat s ON s.gid = n.gid AND s.appid = n.appid
              WHERE n.gid = c.gid AND n.is_patch = true
                AND s.before_review_count > 0 AND s.after_review_count > 0
                AND s.before_positive_pct IS NOT NULL AND s.after_positive_pct IS NOT NULL
                AND (cardinality(:'genre_ids'::integer[]) = 0 OR EXISTS (
                    SELECT 1 FROM game_tag gt WHERE gt.appid = n.appid AND gt.tag_id = ANY(:'genre_ids'::integer[])
                ))
              OFFSET 0
          )
        ORDER BY c.embedding <=> q.embedding LIMIT 30
    ) candidate
), matches AS (
    SELECT 'before' AS version, * FROM before_matches
    UNION ALL
    SELECT 'after' AS version, * FROM after_matches
)
SELECT v.version,
       (SELECT count(*) FROM slots) AS slot_count,
       count(m.chunk_id) AS matched_chunks,
       count(DISTINCT m.gid) AS distinct_patches,
       coalesce(jsonb_agg(jsonb_build_object(
           'slot', m.slot_index, 'chunk_id', m.chunk_id, 'patch_id', m.gid,
           'similarity', round((greatest(0, least(1, 1 - m.distance)) * 100)::numeric, 1), 'text', c.text
       ) ORDER BY m.slot_index, m.distance, m.chunk_id) FILTER (WHERE m.chunk_id IS NOT NULL), '[]'::jsonb) AS evidence
FROM (VALUES ('before'), ('after')) v(version)
LEFT JOIN matches m ON m.version = v.version
LEFT JOIN patch_chunk c ON c.chunk_id = m.chunk_id
GROUP BY v.version
ORDER BY v.version DESC;

ROLLBACK;
