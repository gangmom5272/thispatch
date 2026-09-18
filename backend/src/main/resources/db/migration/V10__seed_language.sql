-- language 표 시드 — 스팀 리뷰 API 가 쓰는 언어 코드 31종
--
-- 2026-09-17 dt=2026-09-17 리뷰 512만 건에서 실제로 나온 코드 30종에 arabic 을 더했다.
-- (malay 는 리뷰 4건뿐이지만 실제로 있어서 넣는다 — 빼면 그 게임의 language_stat 이 빠진다)
--
-- 왜 지금 넣는가
--   language_stat.language_code 가 이 표를 참조한다(fk_language_stat_language).
--   표가 비어 있으면 language_stat 에 한 줄도 못 넣는다. 2026-09-17 적재기를
--   만들다 발견했다 — 아무도 채우는 코드를 만들지 않았다.
--
-- 코드는 스팀이 정한 값(appreviews 의 language 파라미터·리뷰의 language 필드)이다.
-- 우리가 짓지 않는다. 이름은 한국어 표시명이고 20자 안이다.
--
-- ⚠ 리뷰에 여기 없는 코드가 오면 적재기(ReviewStatsToPostgres)가 그 코드와
--   건수를 찍고 뺀다. 그때 여기에 추가하고 다음 번호 마이그레이션으로 넣는다.
--   이 파일은 고치지 않는다 — Flyway 체크섬이 어긋난다.

INSERT INTO "language" ("language_code", "name_ko") VALUES
    ('english',    '영어'),
    ('schinese',   '중국어 간체'),
    ('tchinese',   '중국어 번체'),
    ('russian',    '러시아어'),
    ('spanish',    '스페인어'),
    ('latam',      '스페인어(중남미)'),
    ('brazilian',  '포르투갈어(브라질)'),
    ('portuguese', '포르투갈어'),
    ('german',     '독일어'),
    ('french',     '프랑스어'),
    ('polish',     '폴란드어'),
    ('turkish',    '터키어'),
    ('koreana',    '한국어'),
    ('japanese',   '일본어'),
    ('thai',       '태국어'),
    ('italian',    '이탈리아어'),
    ('ukrainian',  '우크라이나어'),
    ('czech',      '체코어'),
    ('hungarian',  '헝가리어'),
    ('dutch',      '네덜란드어'),
    ('swedish',    '스웨덴어'),
    ('danish',     '덴마크어'),
    ('finnish',    '핀란드어'),
    ('norwegian',  '노르웨이어'),
    ('romanian',   '루마니아어'),
    ('bulgarian',  '불가리아어'),
    ('greek',      '그리스어'),
    ('vietnamese', '베트남어'),
    ('indonesian', '인도네시아어'),
    ('malay',      '말레이어'),
    ('arabic',     '아랍어');
