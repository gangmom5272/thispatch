-- 게임의 플레이 모드 (싱글 · 멀티 · 협동 · PvP …)
--
-- GET /games 계약의 gameSummary.playModes 가 이 값을 쓴다.
-- 스팀 카탈로그 API 응답의 categories.supported_player_categoryids 다.
-- 받고는 있었는데 저장할 자리가 없어서 버리고 있었다 — 계약(S15P21A202-161,
-- 09-14 15:33)이 수집기(S15P21A202-25, 09-14 15:20)보다 13분 늦게 추가돼서
-- 그때는 이 필드가 있는 줄 몰랐다.
--
-- tag / game_tag 와 같은 구조다. weight 만 없다 — 스팀이 순서를 주지 않는다.
--
-- ⚠ categories 에는 두 묶음이 있는데 여기 쓰는 것은 앞의 것뿐이다.
--     supported_player_categoryids   플레이 모드      13종   ← 이것
--     feature_categoryids            기능(도전과제 등)  45종
--   둘이 번호 공간을 나눠 써서 1~82 에 섞여 있다. 번호만 보고 고르면 안 된다.

CREATE TABLE "play_mode" (
    "play_mode_id" INTEGER      NOT NULL,
    "name_ko"      VARCHAR(100) NOT NULL
);

ALTER TABLE "play_mode"
    ADD CONSTRAINT "pk_play_mode" PRIMARY KEY ("play_mode_id");

-- 2026-09-16 스팀 카탈로그 185,640 개 게임을 전수로 훑어서 확인한 13종이 전부다.
-- 표본 추정이 아니다. 괄호 안은 그 모드를 가진 게임 수와 비율.
-- 이름은 appdetails 의 description (l=koreana) 을 그대로 쓴다.
INSERT INTO "play_mode" ("play_mode_id", "name_ko") VALUES
    (1,  '멀티플레이어'),               --  36,191  19.5%
    (2,  '싱글 플레이어'),              -- 176,658  95.2%
    (9,  '협동'),                      --  21,679  11.7%
    (20, 'MMO'),                       --   2,264   1.2%
    (24, '공유 및 분할 화면'),           --  11,412   6.1%
    (27, '크로스 플랫폼 멀티플레이어'),    --   4,987   2.7%
    (36, '온라인 PvP'),                 --  16,063   8.7%
    (37, '로컬 PvP'),                   --   7,483   4.0%
    (38, '온라인 협동'),                --  15,504   8.4%
    (39, '스크린 공유 및 분할 협동'),     --   6,895   3.7%
    (47, 'LAN PvP'),                    --   2,415   1.3%
    (48, 'LAN 협동'),                   --   2,681   1.4%
    (49, 'PvP');                        --  21,355  11.5%

CREATE TABLE "game_play_mode" (
    "appid"        BIGINT  NOT NULL,
    "play_mode_id" INTEGER NOT NULL
);

ALTER TABLE "game_play_mode"
    ADD CONSTRAINT "pk_game_play_mode" PRIMARY KEY ("appid", "play_mode_id");

ALTER TABLE "game_play_mode"
    ADD CONSTRAINT "fk_game_play_mode_game"
        FOREIGN KEY ("appid") REFERENCES "game" ("appid"),
    ADD CONSTRAINT "fk_game_play_mode_play_mode"
        FOREIGN KEY ("play_mode_id") REFERENCES "play_mode" ("play_mode_id");
