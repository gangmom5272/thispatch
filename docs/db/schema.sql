-- ═══════════════════════════════════════════════════════════════════════════
--  디스패치 (Dispatch) — PostgreSQL 17 스키마
--  2026-09-09 · 18 테이블 · 관계 22개
--
--  생성 순서가 FK 의존을 따릅니다. 위에서 아래로 그대로 실행하세요.
--  ddl-auto=validate 로 쓰므로 이 파일이 유일한 정본입니다.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS vector;    -- 의미 검색 (hnsw)
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- 게임 이름 부분 검색

-- ⚠ vector(768) 의 차원은 잠정입니다. 임베딩 모델 확정 후 일괄 치환하세요.
--   topic.centroid 와 patch_change.embedding 이 같은 모델이어야 합니다.


-- ═══════════════════════════════════════════════════════════════════════════
--  A · 마스터 · 코드
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE tag (
    tag_id       INTEGER      PRIMARY KEY,   -- 스팀 tagid. 대리키를 씌우지 않음
    name_ko      VARCHAR(100) NOT NULL,      -- populartags/koreana
    collected_at TIMESTAMPTZ
);

-- ⚠ topic_id 는 고정값입니다. SMALLSERIAL 로 바꾸지 마세요.
--   HDFS /review_topic 과 display_review.topics 가 이 번호를 저장하므로,
--   DB 를 재구축할 때 순서가 바뀌면 전체 기간의 토픽 값이 조용히 틀립니다.
--     1 = 밸런스   2 = 최적화·버그   3 = UI·조작   4 = 운영   5 = BM·과금
--   Spark 분류 코드의 TOPIC_IDS 상수와 아래 시드를 같은 곳에서 관리하세요.
CREATE TABLE topic (
    topic_id      SMALLINT     PRIMARY KEY,
    name_ko       VARCHAR(50)  NOT NULL,
    centroid      vector(768),               -- 최초 계산 전에는 NULL
    sim_threshold NUMERIC(4,3) NOT NULL
);

CREATE TABLE language (
    language_code VARCHAR(20) PRIMARY KEY,   -- 리뷰 응답 language 의 실제 값
    name_ko       VARCHAR(50) NOT NULL       -- 미등록 코드는 원본 코드를 그대로 표시
);

-- ⚠ display_review · language_stat 이 이 테이블을 FK 로 참조합니다.
--   스팀이 새 언어 코드를 내려주면 INSERT 가 터지므로, 배치는 리뷰를 넣기 전에
--   관측한 코드를 language 에 UPSERT 해야 합니다 (name_ko 는 원본 코드 그대로).

CREATE TABLE code (
    code_id    SMALLSERIAL PRIMARY KEY,
    code_group VARCHAR(20) NOT NULL,         -- change_type / direction / player_impact
    code_value VARCHAR(30) NOT NULL,         -- ⚠ 슬롯별 값 목록 미정 (A-2)
    name_ko    VARCHAR(50) NOT NULL,
    sort_order SMALLINT    NOT NULL,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    definition TEXT,                         -- LLM 정규화 프롬프트에 그대로 들어감
    UNIQUE (code_group, code_value)
);


-- ═══════════════════════════════════════════════════════════════════════════
--  B · 수집 원본
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE game (
    appid              BIGINT       PRIMARY KEY,   -- 스팀 부여 · 단일 · 불변
    name               VARCHAR(500) NOT NULL,      -- koreana 요청 기준. 한국어 보장 없음
    developer          VARCHAR(500),               -- 쉼표 구분 (회사 엔티티 흡수)
    publisher          VARCHAR(500),               -- 쉼표 구분
    short_description  TEXT,
    store_url_path     VARCHAR(300),
    release_ts         BIGINT,                     -- 0 / NULL / 예정일을 구분
    is_early_access    BOOLEAN,                    -- ⚠ NULL = 미확인. false 로 채우지 말 것
    is_coming_soon     BOOLEAN,                    -- ⚠ NULL = 미확인
    store_review_count INTEGER,                    -- summary_filtered.review_count
    store_positive_pct SMALLINT,                   -- percent_positive 원본 정수
    capsule_path       VARCHAR(200),               -- 아래 주석 참조
    collected_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_game_name_trgm ON game USING gin (name gin_trgm_ops);

-- capsule_path — 스팀이 준 경로만 저장하고 도메인은 백엔드 응답에서 붙입니다.
--   저장값 예   steam/apps/3340890/890abf73.../capsule_616x353.jpg?t=1788792791
--   조립        'https://shared.cloudflare.steamstatic.com/store_item_assets/' || capsule_path
--   ⚠ cdn.cloudflare.steamstatic.com 은 해시 접두어 게임(40%)에서 404 입니다.
--   수집        Query 요청에 include_assets: true 추가 →
--               assets.asset_url_format 의 ${FILENAME} 을 assets.main_capsule 로 치환
--   UPSERT 시 반드시 COALESCE(EXCLUDED.capsule_path, game.capsule_path) —
--   응답에 assets 가 없는 게임이 8.8% 이고, 그때 기존 값을 지우면 안 됩니다.

CREATE TABLE game_tag (
    appid  BIGINT  NOT NULL REFERENCES game (appid),
    tag_id INTEGER NOT NULL REFERENCES tag (tag_id),
    weight INTEGER NOT NULL,                 -- 확률·퍼센트 아님. 1000 초과 관찰됨
    PRIMARY KEY (appid, tag_id)              -- 18개 중 유일한 복합 PK
);

-- UNIQUE 와 반대 순서. 「이 태그를 가진 게임」 조회용 (유사 사례 필터)
CREATE INDEX idx_game_tag_rev ON game_tag (tag_id, appid);

-- ⚠ tag_id FK 주의 — populartags 사전은 430개인데 게임 응답의 tags[].tagid 가
--   그 안에 없을 수 있습니다 (팀원 문서 지적 사항). 배치가 game_tag 를 넣기 전에
--   미등록 tagid 를 tag 에 UPSERT 하거나(name_ko 는 원본 코드), FK 를 빼야 합니다.

CREATE TABLE news (
    gid          VARCHAR(20)  PRIMARY KEY,   -- ⚠ BIGINT 금지. 아래 주석 참조
    appid        BIGINT       NOT NULL REFERENCES game (appid),
    title        VARCHAR(500) NOT NULL,
    contents     TEXT         NOT NULL,      -- maxlength=0 요청
    url          VARCHAR(1000),
    published_at TIMESTAMPTZ  NOT NULL,      -- 게시 시각. 실제 배포 시각 보장 아님
    feed_type    SMALLINT,                   -- 1=공식 공지 · 0=외부 기사
    feed_tags    VARCHAR(300),               -- 쉼표. patchnotes 가 패치 판정 주 근거
    is_patch     BOOLEAN      NOT NULL,      -- 규칙 기반 판정. 실패 상태 없음
    patch_reason TEXT,
    collected_at TIMESTAMPTZ  NOT NULL
);

-- ⚠ gid 를 BIGINT 로 바꾸면 안 됩니다.
--   공지 11,477건 실측 — 전부 숫자이지만 최댓값이 8,033,928,544,510,244,328 로
--   signed BIGINT 상한(9,223,372,036,854,775,807)의 87.1% 를 이미 씁니다.
--   스팀 GlobalID 는 unsigned 64bit 이므로 상위 비트가 켜진 gid 하나에 INSERT 가 터집니다.
--   gid 는 조인과 동등비교만 하므로 VARCHAR 로 충분합니다.

CREATE INDEX idx_news_patch ON news (appid, is_patch, published_at DESC);
CREATE INDEX idx_news_pub   ON news (appid, published_at DESC);


-- ═══════════════════════════════════════════════════════════════════════════
--  C · 공지 가공
-- ═══════════════════════════════════════════════════════════════════════════

-- ⚠ 정규화 단위 미결. 지금은 「노트 전체 단위」 = 공지 1건 = 1행 (A-3).
--   문장 단위로 바뀌면 PK 가 patch_change_id BIGSERIAL 로, gid 는 FK 컬럼이 되고
--   news → patch_change 가 1:1 식별에서 1:N 비식별이 됩니다.
--   다른 17개 테이블은 영향이 없습니다.
CREATE TABLE patch_change (
    gid                VARCHAR(20) PRIMARY KEY REFERENCES news (gid),
    change_type_id     SMALLINT    REFERENCES code (code_id),
    direction_id       SMALLINT    REFERENCES code (code_id),
    player_impact_id   SMALLINT    REFERENCES code (code_id),
    normalized_summary TEXT,                 -- 고유명사를 일반화. 임베딩 입력
    embedding          vector(768) NOT NULL,
    processed_at       TIMESTAMPTZ           -- 행이 없으면 미처리 (상태 컬럼 없음)
);

-- 약 74만 행 · 벡터 1.06GB 규모라 hnsw 로 충분합니다 (ivfflat 불필요)
CREATE INDEX idx_pc_vec ON patch_change USING hnsw (embedding vector_cosine_ops);


-- ═══════════════════════════════════════════════════════════════════════════
--  D · 리뷰 집계
-- ═══════════════════════════════════════════════════════════════════════════

-- 전체 기간 보관. 14일 롤링으로 줄이면 일별분석 탭이 과거로 못 갑니다.
-- 부정 수는 생성 컬럼 — 배치가 쓰지 않으므로 긍정+부정이 전체와 안 맞을 수 없습니다.
CREATE TABLE daily_stat (
    daily_stat_id         BIGSERIAL PRIMARY KEY,
    appid                 BIGINT  NOT NULL REFERENCES game (appid),
    stat_date             DATE    NOT NULL,
    review_count          INTEGER NOT NULL,        -- 신규 + 수정
    positive_count        INTEGER NOT NULL,
    negative_count        INTEGER GENERATED ALWAYS AS (review_count - positive_count) STORED,
    new_review_count      INTEGER NOT NULL,        -- 첫 작성 (created_ts 기준)
    new_positive_count    INTEGER NOT NULL,
    new_negative_count    INTEGER GENERATED ALWAYS AS (new_review_count - new_positive_count) STORED,
    edited_review_count   INTEGER NOT NULL,        -- 수정 (updated_ts 기준). 신규와 상호 배타
    edited_positive_count INTEGER NOT NULL,
    edited_negative_count INTEGER GENERATED ALWAYS AS (edited_review_count - edited_positive_count) STORED,
    UNIQUE (appid, stat_date)
);

-- edited_* 정확도와 HDFS compaction — 2026-09-10 확정
--
--   HDFS 에서 리뷰의 모든 버전을 보존합니다. 중복 판단 기준은
--   (recommendationid, updated_ts) 로, 위 display_review 의 UNIQUE 와 같은 규칙입니다.
--
--   최신만 남기는 안을 버렸습니다. 9/1 작성 · 9/5 수정 · 9/9 재수정된 리뷰는
--   최신만 보면 9/5 를 영구히 놓치고, 그러면 9/5 의 edited_* 를 다시 만들 수
--   없습니다. 버전 보존 비용은 연 8.7GB 이고 디스크는 4.92TB 있습니다.
--
--   따라서 compaction 의 역할은 이력 정리가 아니라 파일 통합입니다.
--   수집 1회에 파일이 1,974개 나오므로(실측), 그대로 두면 데이터 크기와
--   무관하게 Spark 가 파일 여는 비용만으로 느려집니다.

-- 창이 닫힌 뒤에만 INSERT 합니다 → 행의 존재가 완료 표시.
-- is_complete / observed_through 컬럼이 필요 없습니다.
CREATE TABLE patch_stat (
    gid                 VARCHAR(20) PRIMARY KEY REFERENCES news (gid),
    appid               BIGINT      NOT NULL REFERENCES game (appid),   -- 반정규화
    patched_at          TIMESTAMPTZ NOT NULL,     -- news.published_at 의 캐시
    before_review_count INTEGER     NOT NULL,     -- 전 7일
    before_positive_pct NUMERIC(5,2),             -- 표본 부족이면 NULL
    after_review_count  INTEGER     NOT NULL,     -- 후 7일. 항상 7일치 전수
    after_positive_pct  NUMERIC(5,2),
    delta_pct           NUMERIC(5,2)              -- after − before (%p). 분모 0 이면 NULL
);

CREATE INDEX idx_ps_game ON patch_stat (appid, patched_at DESC);

-- 최근 14일 집계값만. 전후 비교를 걷어냈습니다 (2026-09-09).
-- 경계가 게임별 4분위라 playtime_from/to 를 행마다 들고 있어야 합니다.
CREATE TABLE band_stat (
    band_stat_id   BIGSERIAL PRIMARY KEY,
    appid          BIGINT   NOT NULL REFERENCES game (appid),
    band_no        SMALLINT NOT NULL,             -- 1~4
    playtime_from  INTEGER  NOT NULL,             -- 구간 하한 (분)
    playtime_to    INTEGER,                       -- 4번 밴드는 NULL
    review_count   INTEGER  NOT NULL,             -- 토픽 구성비의 분모
    positive_count INTEGER  NOT NULL,             -- 부정 = review_count − positive_count
    UNIQUE (appid, band_no)
);

-- 불만요소 탭 = 「부정 리뷰의 토픽 구성」이므로 부정만 셉니다.
-- 부정 리뷰가 0건인 (밴드, 토픽) 조합은 행을 만들지 않습니다 → 화면은 「리뷰 없음」.
-- 다중 라벨(리뷰당 평균 1.8토픽)이라 합이 밴드 부정 총수를 넘을 수 있습니다.
CREATE TABLE band_topic_stat (
    band_topic_stat_id BIGSERIAL PRIMARY KEY,
    band_stat_id       BIGINT   NOT NULL REFERENCES band_stat (band_stat_id) ON DELETE CASCADE,
    topic_id           SMALLINT NOT NULL REFERENCES topic (topic_id),
    negative_count     INTEGER  NOT NULL,
    UNIQUE (band_stat_id, topic_id)
);

CREATE TABLE language_stat (
    language_stat_id BIGSERIAL PRIMARY KEY,
    appid            BIGINT      NOT NULL REFERENCES game (appid),
    language_code    VARCHAR(20) NOT NULL REFERENCES language (language_code),
    review_count     INTEGER     NOT NULL,        -- 리뷰 수 상위 5개 언어만
    positive_count   INTEGER     NOT NULL,
    UNIQUE (appid, language_code)
);


-- ═══════════════════════════════════════════════════════════════════════════
--  E · 화면용 리뷰
-- ═══════════════════════════════════════════════════════════════════════════

-- 최근 14일(created_ts 기준) 리뷰를 버전까지 전부 담습니다.
-- 수정 전/후는 내용이 다르므로 각각 별개 행이고, 화면에 둘 다 보여줍니다.
-- 같은 리뷰의 모든 버전이 created_ts 를 공유해 같이 들어오고 같이 나갑니다.
CREATE TABLE display_review (
    review_id          BIGSERIAL   PRIMARY KEY,   -- 우리가 부여
    recommendationid   BIGINT      NOT NULL,      -- 스팀 값. 버전마다 중복
    appid              BIGINT      NOT NULL REFERENCES game (appid),
    review_text        TEXT        NOT NULL,
    voted_up           BOOLEAN     NOT NULL,
    votes_up           INTEGER     NOT NULL,      -- 0~14일 리뷰의 85.7% 가 0표
    playtime_at_review INTEGER,                   -- 결측 허용. NULL 이면 band_no 도 NULL
    language_code      VARCHAR(20) NOT NULL REFERENCES language (language_code),
    band_no            SMALLINT,                  -- 1~4. 1:1 이라 분리 불가
    topics             SMALLINT[],                -- topic_id 배열
    created_ts         BIGINT      NOT NULL,      -- 14일 창 기준
    updated_ts         BIGINT      NOT NULL,      -- 수정 한 번에 한 번 바뀜
    UNIQUE (recommendationid, updated_ts)         -- 버전 식별 + 배치 UPSERT 키
);

-- is_edited 컬럼은 없습니다 — created_ts != updated_ts 이거나
-- 같은 recommendationid 행이 2개 이상이면 수정된 것입니다.

CREATE INDEX idx_dr_votes  ON display_review (appid, votes_up DESC);
CREATE INDEX idx_dr_band   ON display_review (appid, band_no, votes_up DESC);
CREATE INDEX idx_dr_lang   ON display_review (appid, language_code, votes_up DESC);
CREATE INDEX idx_dr_topics ON display_review USING gin (topics);
CREATE INDEX idx_dr_win    ON display_review (appid, created_ts DESC);


-- ═══════════════════════════════════════════════════════════════════════════
--  F · 회원
-- ═══════════════════════════════════════════════════════════════════════════

-- 자체 가입(LOCAL)과 스팀 OpenID 2.0(STEAM) 둘 다 지원.
-- 별도 인증 테이블은 만들지 않습니다 (B-3).
CREATE TABLE app_user (
    app_user_id   BIGSERIAL    PRIMARY KEY,
    login_type    VARCHAR(10)  NOT NULL,        -- LOCAL / STEAM
    email         VARCHAR(255) UNIQUE,          -- LOCAL 필수. STEAM 전용은 NULL
    password_hash VARCHAR(100),                 -- LOCAL 필수. 평문 저장 금지
    steam_id      VARCHAR(20)  UNIQUE,          -- OpenID claimed identity 에서 추출
    nickname      VARCHAR(50),
    status        VARCHAR(10)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ,
    CONSTRAINT chk_login_method
        CHECK (email IS NOT NULL OR steam_id IS NOT NULL)
);

CREATE TABLE my_game (
    my_game_id  BIGSERIAL   PRIMARY KEY,
    app_user_id BIGINT      NOT NULL REFERENCES app_user (app_user_id) ON DELETE CASCADE,
    appid       BIGINT      NOT NULL REFERENCES game (appid),
    created_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (app_user_id, appid)
);


-- ═══════════════════════════════════════════════════════════════════════════
--  G · 배치 운영
-- ═══════════════════════════════════════════════════════════════════════════

-- 실행 1회의 기록. INSERT 만 하고 매일 4~6행씩 쌓입니다.
-- 지워도 수집은 정상 동작합니다 (재개 좌표는 collect_progress).
CREATE TABLE batch_job (
    batch_job_id      BIGSERIAL   PRIMARY KEY,
    job_name          VARCHAR(50) NOT NULL,      -- 카탈로그/리뷰/공지/분류/집계/Serving
    run_mode          VARCHAR(10),               -- BACKFILL / DAILY / REPROCESS
    started_at        TIMESTAMPTZ NOT NULL,      -- 매일 00시 KST = 15:00 UTC
    finished_at       TIMESTAMPTZ,               -- 미완료는 NULL
    status            VARCHAR(10) NOT NULL,      -- RUNNING/SUCCEEDED/PARTIAL/FAILED
    input_count       INTEGER,                   -- 읽은 건수
    output_count      INTEGER,                   -- 쓴 건수. 단계마다 단위가 다름
    error_count       INTEGER,
    error_summary     TEXT,                      -- 원문·개인정보 복사 금지
    processing_config JSONB                      -- 집계 시간대·기간 기준·구간 경계·버전
);

CREATE INDEX idx_bj_recent ON batch_job (job_name, started_at DESC);

-- 어디까지 받았는지. UPSERT 만 하고 행 수가 약 23.3만으로 고정입니다.
-- 지우면 1.76억 건을 처음부터 다시 받아야 합니다.
CREATE TABLE collect_progress (
    collect_progress_id BIGSERIAL   PRIMARY KEY,
    appid               BIGINT      NOT NULL REFERENCES game (appid),
    source_type         VARCHAR(10) NOT NULL,    -- REVIEW / NEWS. 카탈로그는 워터마크 없음
    watermark_ts        BIGINT,                  -- 완료가 확인된 경계
    cursor_token        TEXT,                    -- BACKFILL 전용. DAILY 는 항상 NULL
    boundary_ids        TEXT,                    -- 워터마크와 같은 초의 ID 목록
    last_batch_id       BIGINT      REFERENCES batch_job (batch_job_id),
    last_run_at         TIMESTAMPTZ,             -- 이번 실행이 이 게임을 만졌나
    last_count          INTEGER,                 -- 어제 받은 건수 (이상 감지)
    UNIQUE (appid, source_type)
);

-- boundary_ids 가 필요한 이유 — 공지 enddate 경계가 inclusive 입니다.
--   실측: enddate=1786669622 로 요청하면 그 시각의 공지가 다시 옵니다.
--   워터마크를 그대로 넣으면 매일 같은 공지를 받고,
--   1초를 빼면 같은 초의 미수집 공지를 영구히 잃습니다.
--   → enddate = watermark_ts 로 요청하고, boundary_ids 에 있는 gid 만 버립니다.

-- cursor_token 을 DAILY 에서 쓰지 않는 이유 —
--   실측: cursor 재사용은 100% 재현되지만, 한 글자만 틀리면 HTTP 200 으로
--   엉뚱한 100건이 오고 완전히 깨지면 0건 + cursor 없음이라
--   4-strike 가 「수집 완료」로 오판합니다.
--   정렬(filter=updated 내림차순 위반 0.33%)이 신뢰할 수준이므로
--   DAILY 는 매일 cursor=* 부터 watermark_ts 까지 받습니다.
--   CS2 백필 14,900페이지 vs 하루 증분 9페이지 — 1,655배 차이라 감당됩니다.


-- ═══════════════════════════════════════════════════════════════════════════
--  주요 배치 · 조회 쿼리
-- ═══════════════════════════════════════════════════════════════════════════

-- ① patch_stat 집계 대상 — 창이 닫혔는데 행이 없는 것 (하루 약 1,300건)
--    「patched_at 이 정확히 8일 전」으로 잡으면 안 됩니다.
--    판정이 늦은 공지·백필 유입·배치 1일 실패를 영구히 놓칩니다.
/*
SELECT n.gid
FROM news n
LEFT  JOIN patch_stat       p  ON p.gid = n.gid
INNER JOIN collect_progress cp ON cp.appid = n.appid AND cp.source_type = 'REVIEW'
WHERE n.is_patch = TRUE
  AND n.published_at <= NOW() - INTERVAL '7 days'
  AND cp.watermark_ts >= EXTRACT(EPOCH FROM n.published_at + INTERVAL '7 days')
  AND p.gid IS NULL;
*/

-- ② 불만요소 탭 — 밴드별 토픽 구성비 (분모는 부모에서)
/*
SELECT bts.topic_id,
       bts.negative_count,
       100.0 * bts.negative_count
            / NULLIF(bs.review_count - bs.positive_count, 0) AS share_pct
FROM band_topic_stat bts
JOIN band_stat bs ON bs.band_stat_id = bts.band_stat_id
WHERE bs.appid = ? AND bs.band_no = ?;
*/

-- ③ 일별분석 탭 — 날짜별 그래프
/*
SELECT stat_date, review_count, positive_count,
       new_review_count, edited_review_count
FROM daily_stat
WHERE appid = ? AND stat_date BETWEEN ? AND ?
ORDER BY stat_date;
*/

-- ④ 대표 리뷰 — AI 요약 입력. 수정 전/후 중복을 뺄지는 미결
/*
SELECT DISTINCT ON (recommendationid) review_text, voted_up, playtime_at_review
FROM display_review
WHERE appid = ? AND band_no = ?
ORDER BY recommendationid, updated_ts DESC;
*/

-- ⑤ 토픽 필터 — 배열 + GIN 인덱스
/*
SELECT review_id, review_text
FROM display_review
WHERE appid = ? AND band_no = 2 AND topics @> ARRAY[2::smallint]
ORDER BY votes_up DESC LIMIT 20;
*/


-- ═══════════════════════════════════════════════════════════════════════════
--  남은 미정 (DDL 에 영향 있는 것만)
-- ═══════════════════════════════════════════════════════════════════════════
--  1. patch_change 정규화 단위      노트 전체(현재) vs 문장 단위 → PK·관계 1곳
--  2. code 슬롯 값 목록 (A-2)        code_value 시드
--  3. 임베딩 모델                    vector(768) 의 차원
--  4. 긍정률 표시 최소 표본           화면 규칙. 「5건 + n 병기」 권고
--  5. game 의 키 누락 해석            is_early_access / is_coming_soon
--  6. B-7 배치 재수행 정책            batch_job 컬럼만 늘 수 있음
