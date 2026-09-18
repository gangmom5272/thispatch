-- topic 표 시드 — 리뷰 토픽 분류기가 붙이는 토픽 5종
--
-- 왜 지금 넣는가
--   review_topic.topic_id 와 band_topic_stat.topic_id 가 이 표를 참조한다
--   (fk_review_topic_topic · fk_band_topic_stat_topic). 표가 비어 있으면 AI 가 붙인
--   토픽을 한 줄도 못 넣는다. 2026-09-18 적재기(ReviewTopicToPostgres)를 만들다
--   발견했다 — language(V10)와 같은 상황이었다.
--
-- 값의 출처 (2026-09-18 진우님 확정 · ai/CONTRACT.md 3-3 · ai/batch/classify_reviews.py)
--   topic_id 는 AI 쪽 고정값이다. 파케이의 topic_id 가 이 번호로 온다 — 바꾸면 안 된다.
--   sim_threshold 는 이름과 달리 코사인 유사도가 아니라 **분류기 확률 문턱**이다.
--   review_topic.score 가 그 확률이고, 문턱 이상인 행만 파일에 들어 있다.
--   centroid 는 지금 쓰지 않아 null 로 둔다. name_ko 는 화면 글자라 프론트에서 바꿔도 된다.
--
-- ⚠ 이 파일은 고치지 않는다 — Flyway 체크섬이 어긋난다. 값이 바뀌면 다음 번호로 넣는다.

INSERT INTO "topic" ("topic_id", "name_ko", "centroid", "sim_threshold") VALUES
    (1, '밸런스',   NULL, 0.600),
    (2, '버그·성능', NULL, 0.575),
    (3, 'UI·조작',  NULL, 0.700),
    (4, '운영·서버', NULL, 0.700),
    (5, '가격·과금', NULL, 0.600);
