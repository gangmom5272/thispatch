# 디스패치 프로젝트 인수인계

> 다른 Claude 세션이 이 프로젝트를 이어받기 위한 문서입니다.
> 최종 갱신 2026-09-06.
> **먼저 「⚠️ 폐기된 수치」 절을 읽으세요.** 옛 문서에 남아 있는 틀린 값들이 있습니다.

---

## 1. 무엇을 만드는가

**디스패치 (Dispatch)** — 스팀 게임의 리뷰와 패치노트를 분석해, 게임사가 패치를 기획할 때 참고할 수 있는 지표를 주는 플랫폼.

SSAFY 팀 A202 의 빅데이터 분산처리 관통 프로젝트입니다. 사용자는 게임 개발사/기획자를 가정합니다.

### 문제 정의
패치에 대한 유저 반응은 기존 지표(스토어 긍정률)에 잡히지 않습니다. 긍정률은 누적값이라 개별 패치의 영향이 묻힙니다. 그래서 패치 전후 창을 잘라 반응을 분해합니다.

### 화면 7개 (목업 기준)
| 탭 | 보여주는 것 |
|---|---|
| Daily | 일별 리뷰 수 · 첫작성/수정 비율 · 긍정률 · 패치 네비게이터 |
| Reviews | 대표 리뷰 5건 + AI 요약 + 토픽 필터 검색 |
| Topics | 플레이타임 4구간별 긍정률 · 토픽 구성 |
| Lang | 언어별 긍정률 before/after + 대표 리뷰 |
| Plan | 기획안 입력 → 슬롯 재진술 |
| Cases | 유사 패치 사례 검색 |
| Detail | 패치노트 원문 · 정규화 결과 |

**중요** — 과거 패치 조회는 Daily 탭에서만 지원합니다. 나머지는 최신 패치 고정입니다. 이 결정이 저장량을 30배 줄입니다.

---

## 2. 산출물 위치

### 노션 (주 문서)
| 페이지 | ID |
|---|---|
| **데이터 정리** (메인) | `3c39f3f2-7852-80c1-aa6d-cbbc0edd5004` |
| 추가적으로 고려할 사항(컨님 피드백) | `3c39f3f2-7852-80a7-937c-d3767fad85e0` |
| API / 데이터 소스 검증 | `b053cb0cce434b269aa10b2b292e496d` |
| 조사 템플릿 | `3d19f3f2-7852-8022-878a-c909d2758966` |

「데이터 정리」 구조 — 실측 요약(쉬운 버전) / 1 필요한 데이터 / 2 수집 / 3 가공 / 4 저장 / 5 논리적 설계 / 6 물리적 설계 / 7 정해야 할 것

### 노션 · INFRA 열 (2026-09-09 신규)
| 페이지 | ID |
|---|---|
| 클러스터 구성 | `3d69f3f2-7852-8126-938b-eb7cb66e1ec0` |
| 워커 노드 설치 (노트북 2~5) | `3d69f3f2-7852-81e1-b9a7-e542ac9924b1` |
| 개발환경 버전 | `3d69f3f2-7852-8123-b4f6-f406707da3cb` |

### 로컬 — 2026-09-09 저장소 구조 개편됨
`scripts/` 를 역할별로 쪼갰습니다. 예전 경로는 이제 없습니다.

```
C:\Users\SSAFY\Desktop\S15P21A202\      (git 추적됨 · GitLab master)
  backend\                    Spring Boot 3.5.16 · Gradle · com.ssafy.thispatch
  infra\                      클러스터 구축·운영 스크립트 01~09
  bench\collect_soak.py       지속 수집 검증 도구       (구 scripts\)
  bench\spark_bench.py        Spark 집계 벤치마크       (구 scripts\)
  bench\results\              측정 결과 json
  docs\                       설계 문서 (이제 git 추적)
  docs\db\schema.sql          최종 DDL — 테이블 18개. 이게 정본
  docs\db\schema.html         필드 명세 (브라우저로 열면 표로 보임)
  subtask\                    사전 과제 기간 산출물 (개인 TIL·하둡 과제)
  .gitignore                  수집 데이터·빌드 산출물 차단

  scripts\out\                리뷰 원본 4.3GB · 1,979 파일  ← 저장소 밖으로 옮길 예정
  scripts\out_20260906_*\     리뷰 원본 2.2GB · 1,996 파일

C:\Users\SSAFY\Downloads\Steam 운영 진단 플랫폼 (standalone).html   목업 23MB
```

목업은 데이터가 `<script>` 안에 있습니다. 주요 상수: `PT_BANDS` `TOPICS_BY_BAND` `LANGS` `REVIEW_POOL` `AI_SUMMARY` `PATCH_WINDOWS` `CASE_STATS`. 탭 키는 `daily/reviews/topics/lang/plan/cases/detail`.

### 측정 환경
```
CPU   Intel Core Ultra 9 185H · 16코어 22스레드 (P/E 혼합)
GPU   RTX 4070 Laptop · VRAM 8GB (8,188 MiB)
RAM   63.5 GB
디스크 1.6TB 여유
Java  C:\Users\SSAFY\.jdks\ms-17.0.20 (JDK 17)
Python 3.11.9 · pyspark 4.2.0
```

---

## 3. ⚠️ 폐기된 수치 — 절대 다시 쓰지 말 것

옛 문서·발표자료에 남아 있을 수 있습니다. 모두 실측으로 뒤집혔습니다.

| 항목 | 틀린 값 | 맞는 값 | 왜 틀렸나 |
|---|---|---|---|
| **수집 방식** | 매일 전량 재수집 | **매일 증분** | `filter=updated` 가 수정까지 잡아줌 |
| 전량 수집 시간 | 5.7시간 → 14시간 | **15.6시간** (최초 1회만) | 임계경로만 계산 + 스토어 카운트가 steam 구매만 센 값 |
| 매일 수집 시간 | 14시간 | **1~2분** | 하루 증분이 전체의 0.03% |
| 수정 리뷰 비율 | 4.5% | **13.68%** | 최근 리뷰만 본 표본 |
| 수정 간격 당일 | 58.2% | **14.1%** | 표본 9만 건 → 364만 건으로 반전 |
| 수정 간격 30일 | 91.9% | **28.2%** | 동일 |
| 수정 간격 최대 | 210일 | **5,680일 (15.6년)** | 동일 |
| 전체 리뷰 수 | 2억 → 1.5억 | **1.67억** | 1.5억은 스팀 구매자만. `purchase_type=all` 은 18% 더 |
| GPU VRAM | 12GB | **8GB** | 실측 |
| `language_stat` 행 수 | 4.1만 | **8.2만** | `phase_type` 누락 |
| 기획안 헤드라인 | 표본 +56% · 긍정률 −11.7%p | **재현 안 됨** | 9게임 502일 55,617건에서 중앙 +0.0% |

마지막 항목 대체 근거: **수정 리뷰의 긍정률이 첫 작성 리뷰보다 −20.3%p** (첫작성 91.1% vs 수정 70.8%), 9게임 전부에서 일관.

---

## 4. 실측값 전부

### 4-1. 카탈로그 · 규모
```
전체 게임          184,604개  (하루 사이 183,980~184,606 으로 흔들림)
수집 대상           8,409개  (리뷰 1,000건 이상)  ← 모든 규모 산정의 전제
전체 리뷰          1억 6,715만 건  (purchase_type=all)
                  1억 4,165만 건  (스토어 표시 = purchase_type=steam)
공지               게임당 중앙 194건 · 평균 459건 · 최대 5,621건(War Thunder)
태그               430개
```
게임 규모 분포: 리뷰 1천~1만 6,482개 / 1만~10만 1,529개 / 10만 이상 228개.
**게임 4.5%가 리뷰 94.5%를 차지합니다.**

### 4-2. 지속 수집 (2026-09-06 · 5시간 연속)
| 동시성 | 지속 처리율 | 직전 대비 | p50 |
|---|---|---|---|
| 8 | 1,349 리뷰/초 | — | 546ms |
| 16 | 2,057 | ×1.53 | 744ms |
| 32 | 2,604 | ×1.27 | 1,138ms |
| **48** | **2,967** | ×1.14 | 775ms |

```
약 3,000 리뷰/초에서 포화. 순간 최고 3,578.
요청 275,392건 · 리뷰 26,623,492건
429 발생 0회                          ← 차단 없음
기타 오류 65건 (커넥션 타임아웃 · 0.024%)
전량 수집   총 처리량 기준 14.0시간 / 임계경로 기준 9.7시간 → 약 14시간
임계경로    CS2 russian 2,709,665건 (5시간에 51.6%)
```

### 4-3. 병목은 회선이 아니라 스팀 서버
| 실험 | 결과 | 해석 |
|---|---|---|
| gzip 요청 (전송량 6.7배 감소) | 394 → 312ms (1.26배) | 대역폭 병목이면 6.7배 빨라져야 함 |
| keep-alive 연결 재사용 | 336 → 278ms (1.2배) | 핸드셰이크도 아님 |
| 최고치 대역폭 | 18 Mbps | 가정용 회선에도 여유 |

요청당 280~340ms 중 대부분이 스팀 서버 응답 생성 시간. **서버·회선을 바꿔도 비슷합니다.**
단 gzip 은 켜야 합니다 (18 Mbps → 2.7 Mbps).

### 4-4. ⚠️ 수집기 구현 시 필수 — 빈 페이지
**appreviews 는 순회 도중 빈 응답을 돌려줍니다.** 분당 2~8건.
이걸 「파티션 끝」으로 처리하면 CS2 russian 271만 건 중 3만 건(**1.2%**)만 받고 종료합니다.

해결: **빈 페이지가 4번 연속 나와야 끝으로 인정.** 수정 후 완료 1,951개 파티션 전부 90% 이상 수집(100.0%), 140만 건 파티션도 완주.

### 4-5. 리뷰 수정 특성 (표본 2,662만 건 / 수정 364만 건)
수정 비율은 리뷰 나이에 따라 오릅니다.
| 작성 후 경과 | 리뷰 수 | 수정된 비율 |
|---|---|---|
| 7일 이내 | 36,723 | 3.12% |
| 7~30일 | 151,044 | 4.31% |
| 30~90일 | 412,643 | 5.41% |
| 90일~1년 | 2,108,027 | 8.87% |
| 1~3년 | 7,020,588 | 14.13% |
| 3년 이상 | 16,894,467 | 14.39% |

전체 평균 **13.68%**.

수정 간격 누적 분포:
| 작성 후 | 누적 | 이 창으로 자르면 놓치는 수정 |
|---|---|---|
| 당일 | 14.1% | 85.9% |
| 7일 | 21.3% | 78.7% |
| 30일 | 28.2% | **71.8%** |
| 90일 | 36.0% | 64.0% |
| 1년 | 55.7% | 44.3% |
| 3년 | 83.0% | 17.0% |
| 최대 | 5,680일 (15.6년) | — |

**이 표는 `filter=recent` 로는 증분이 불가능하다는 근거입니다.** `filter=recent` 는 작성일 순 정렬이라 (created 위반 0건 vs updated 6~14건) 오래전 작성 후 최근 수정된 리뷰가 앞으로 올라오지 않습니다. 되돌아보기 창을 어떻게 잡아도 놓칩니다.
→ **해결은 `filter=updated`.** 10절 참조. 삭제는 여전히 감지 불가이고, 감지하지 않기로 결정했습니다.

### 4-6. Spark 집계 (2026-09-06 · 리뷰 2,662만 건)
| 코어 | 읽기 | 집계 | 실질 | 스케일 | 1.5억 환산 |
|---|---|---|---|---|---|
| 1 | 128.2초 | 44.1초 | 172.3초 | — | 16.2분 |
| 2 | 71.6 | 25.2 | 96.7 | ×1.78 | 9.1분 |
| 4 | 48.1 | 20.5 | 68.5 | ×2.52 | 6.4분 |
| **8** | **35.0** | **18.9** | **54.0** | **×3.19** | **5.1분** |
| 16 | 35.2 | 22.6 | 57.9 | ×2.98 | 5.4분 |

```
8코어 최적. 16에서 퇴보 (P/E 코어 혼합 CPU).
스케일아웃 효율 1→2 89% · 2→4 70% · 4→8 63%
병목은 읽기 (54초 중 35초 = 65%), 집계는 18.9초
JSONL.gz 기준이므로 상한값. Parquet 이면 읽기가 크게 줄어듦.
```

**주의** — 노트북 1대의 코어 분할이므로 진짜 다중 노드 스케일아웃과 다릅니다. 발표 시 명시 필요.

### 4-7. 하루 예산
```
[최초 1회]
수집        15.6시간   실측
임베딩       4.6시간   추정 · 미검증
          ─────────
          20.2시간

[매일 · 증분]
수집        1~2분     실측 (약 4.8만 건)
토픽 분류    수 초      증분분만
Spark 집계   5분       실측 (8코어)
AI 요약      ?         대표 리뷰가 바뀐 게임만
          ─────────
          10분~1시간 / 24시간
```

### 4-8. 저장 규모
```
HDFS Parquet   1.67억 건 · 약 26GB (Snappy)
  압축 실측     무압축 235.2 B/행 → Snappy 146.5 → GZIP 96.4
  구조          /review_raw/base/ + /review_raw/delta/dt=날짜/
                집계 시 review_id 기준 최신 updated_ts 만. 주 1회 compaction

PostgreSQL
  daily_stat        약 3,425만 행 · 약 2GB   ← 가장 큰 테이블
  patch_stat        약 75만
  band_topic_stat   약 19.6만  ← 재추정. 부정만 세면 더 줄어듦
  language_stat     8.2만
  band_stat         6.6만
  display_review    약 54만 · 329MB (행당 452B)
  game_tag          약 184만
  news              약 160만
  game              184,389
```
`patch_summary` 8.2만 행은 없어졌다 — AI 요약을 저장하지 않기로 했다.
`band_topic_stat` 은 32.9만 → 약 19.6만으로 재추정됐고, 부정 리뷰만 세기로 해서 더 줄어든다.
**세 집계 테이블(band_stat · band_topic_stat · language_stat)의 행 수는 다시 계산해야 한다.**

`daily_stat` 산정 근거: 게임 132개 표본의 관측일수를 규모별로 나눠 8,239개로 외삽.
리뷰 1천~1만 평균 1,800일 / 1만~10만 3,081일 / 10만 이상 3,259일 × 2채널.

### 4-9. 그 외 실측
```
review_id (recommendationid)  스팀 전역 유일. 게임 5개 624건 겹침 0건,
                              크기순 정렬 시 게임 전환 355회 (게임별 시퀀스면 4회여야 함)
개발사 2곳 이상 8.2% · 퍼블리셔 2곳 이상 9.7% · 최대 13곳 · 퍼블리셔 없음 1.8%
creator_clan_account_id 는 스튜디오 ID 아님 (커뮤니티 허브 ID). ID 36135791 = EA 허브에 37개사
CAPCOM 표기 6가지 → 이름으로도 묶을 수 없음

patchnotes 태그는 공식 공지의 36.7%에만 붙음 (CS2 80% · Warframe 5%)
패치 판정 규칙 재현율 98%+ (태그 held-out, 공식 공지 2,157건 / 정답 612건)
본문 구조(불릿) 판정 실패 — 변경 동사 개수만 유효한 본문 신호

토픽 분류 커버리지 42.1% · 평균 매칭 1.8개
실제 패치는 토픽 구성 10~28%p 이동 / 단순 공지는 2~5%p
리뷰 중앙 길이 34바이트 · 30바이트 초과가 55% (약 8,250만 건)

대표 리뷰 helpful >= 20 은 12게임 중 5게임에서 0건 → 임계값 폐기, 정렬만
votes_up 분포: 1표+ 15.3% · 5표+ 2.7% · 10표+ 1.3% · 20표+ 0.5%
전체 언어 혼합 top5 는 영어 독식 아님 (english 36.0% · russian 26.0%)
밴드 4분위는 정의상 균등. 경계는 패치마다 1.1~1.7배 흔들림
누적 기준 고정 시 창 리뷰가 중앙 1.5배 · 최대 2.3배 편중

summary_unfiltered 는 게임 17개 중 4개에만 옴 (오프토픽 필터가 걸린 게임만)
  → 규칙: unfiltered 가 오면 그것을, 없으면 filtered 를 쓴다 (없으면 filtered 가 곧 전량)

USER 는 PostgreSQL 예약어 (MEMBER·LANGUAGE·TAG·TOPIC·CODE·NEWS 는 아님)
```

---

## 5. 확정된 설계 결정

### 저장
- HDFS(Parquet, appid 파티션) 전량 + PostgreSQL 집계·화면용
- **매일 증분 수집 (2026-09-06 결정).** `filter=updated` 로 수정일 내림차순 순회 + 게임별 워터마크.
  `/review_raw/base/` + `/review_raw/delta/dt=날짜/` 두 층. 집계 시 `review_id` 기준 최신 `updated_ts` 만 취하고, 주 1회 compaction.
  **삭제는 감지하지 않기로 결정.**
- 토픽 분류 결과는 `/review_topic` 에 분리 (centroid 변경 시 원본 22GB 재작성 회피)
- 집계도 매일 전량 재계산 (수정일 기준이라 과거 숫자가 계속 변함)
- **compaction 은 버전을 보존한다 (2026-09-10 확정).**
  중복 판단 기준은 `(recommendationid, updated_ts)` — `display_review` 의 UNIQUE 와 같다.
  완전히 같은 행만 걷어내고 옛 버전은 남긴다. 최신만 남기면 `daily_stat` 의
  `edited_*` 를 다시 만들 수 없기 때문이다. 비용은 연 8.7GB, 디스크는 4.92TB 있다.
- **compaction 의 역할은 이력 정리가 아니라 파일 통합이다.**
  수집 1회에 파일 1,974개가 나왔다(실측). 작은 파일이 쌓이면 데이터 크기와
  무관하게 Spark 가 느려진다. 수천 개 → 수십 개로 합친다.
- **착수는 delta 가 쌓인 뒤에 한다.** 수집기가 없으면 시험할 데이터가 없다.

### 테이블 18개 — 2026-09-09 확정. `docs/db/schema.sql` 이 정본
```
마스터   tag · topic · language · code
원본     game · game_tag · news
공지가공  patch_change
집계     daily_stat · patch_stat · band_stat · band_topic_stat · language_stat
화면용   display_review
회원     app_user · my_game
배치     batch_job · collect_progress
```

20개에서 줄어든 경위 — **ERD 그릴 때 아래 세 개를 넣지 말 것.**

| 없어진 것 | 이유 |
|---|---|
| `game_term` | 슬롯에서 `target_side` 를 빼면서 쓸 데가 없어짐 |
| `patch_summary` · `review_summary` | AI 요약을 저장하지 않고 필요할 때 만들기로 결정 |
| `patch_review` → `display_review` | 이름만 변경. 패치 전용이 아니라 화면에 보여줄 리뷰 전반 |

관계 22개. 복합 PK 는 `game_tag` 하나뿐이다.

PK 규칙: **우리가 만드는 행은 대리키 + UNIQUE.** 외부 시스템의 불변 단일 식별자는 그대로 PK.
- 자연키 PK: `game.appid` `news.gid` `tag.tag_id` `language.language_code` `patch_stat.gid` `patch_change.gid` `topic.topic_id`
- **`display_review` 는 자연키를 쓸 수 없다.** 스팀 `recommendationid` 는 리뷰가 수정되면
  같은 값으로 다시 오므로 PK 가 못 된다. `review_id BIGSERIAL` 을 우리가 부여하고
  `UNIQUE (recommendationid, updated_ts)` 로 버전을 구분한다
- **`news.gid` 는 VARCHAR(20). BIGINT 로 바꾸면 안 된다.** 공지 11,477건 실측 최댓값이
  8,033,928,544,510,244,328 로 signed BIGINT 상한의 87.1% 다. 스팀 GlobalID 는
  unsigned 64bit 이라 상위 비트가 켜진 gid 하나에 INSERT 가 터진다
- **`topic.topic_id` 는 SMALLSERIAL 금지.** 1~5 를 코드가 의미로 참조한다
  (1=밸런스 2=최적화·버그 3=UI·조작 4=운영 5=BM·과금)
- **`game_tag` 만 복합 PK** (`appid`, `tag_id`). 다른 테이블이 참조하지 않아 키 전파 문제가 없다
- `band_topic_stat` 은 `band_stat_id` 를 FK 로 참조 (비식별관계) → 키 전파 차단
- **UNIQUE 필수.** 매일 전량 재적재하므로 없으면 중복 행이 조용히 들어가 화면 숫자가 두 배가 됨

네이밍: 테이블 단수 snake_case / PK `{테이블}_id` / 시각 `_at` / 스팀 epoch `_ts` / 불리언 `is_` / 개수 `_count` / 비율 `_pct` / 구분 `_type`
**`user` 는 예약어라 `app_user`** (자바 클래스는 `User`, `@Table(name="app_user")`)

### 수집
- API 6개 전부 **키 불필요**
  ```
  ① IStoreQueryService/Query        GET · input_json URL 파라미터 · 1000/콜
  ② IStoreBrowseService/GetItems    GET · 200/콜
  ③ store.../appreviews/{appid}     커서 · (appid, language) 파티션
  ④ ISteamNews/GetNewsForApp        feed_type=1 이 공식 공지
  ⑤ tagdata/populartags/{koreana|english}
  ⑥ 스팀 OpenID 로그인
  ```
- `ISteamUser/GetPlayerSummaries` **사용 안 함** (키 필요). 닉네임은 가입 시 입력
- `IPlayerService/GetOwnedGames` **사용 안 함**. 나의 게임은 검색해서 추가
- `appdetails` 소유권 검증 **폐기** (OpenID 는 본인 증명만, 개발사 증명 불가)

### 가공
- 토픽 5개 (`balance` `bug` `ui` `ops` `bm`), centroid 코사인 유사도, 다중 라벨
- 키워드 매칭 대신 임베딩 (fps=장르 vs 프레임 구분 불가). 다국어 모델이라 한국어 centroid 가 영어·중국어에도 통함
- 벡터는 저장 안 함 (768차원 전량이면 600GB)
- 대상은 30바이트 초과 리뷰만
- 대표 리뷰: 패치 후 7일 창 · 30바이트 필터 · `votes_up` 내림차순 · 5건 · 임계값 없음
- 밴드 경계는 패치 전후 14일 리뷰의 `playtime_at_review` 4분위, **패치마다 재계산**
- **`patch_stat` 만** 패치 전 7일 / 후 7일 비교를 한다
- **`band_stat` · `band_topic_stat` · `language_stat` 은 전후 비교를 하지 않는다 (2026-09-09 변경).**
  최근 14일 구성비만 들고 있다. 이유는 실측 — 언어별 긍정률 증감이 95% 신뢰구간을
  넘는 조합이 5.5% 뿐이었다 (패치 시점에 맞춰도 4.6%). `prev_*` `delta_*` 컬럼을 전부 걷어냈다
- `band_topic_stat` 은 **부정 리뷰만** 센다 (불만요소 탭이므로). 0건 조합은 행을 만들지 않고
  화면에서 「리뷰 없음」으로 표시한다
- 5건 못 채우는 언어·밴드는 제외
- AI 요약: 게임당 10개 (리뷰탭 1 + 밴드 4 + 언어 5) = 약 8.2만 개. `source_ids` 비교해 바뀐 것만 재생성
- 패치 판정: 태그 → 부정패턴 → 출시표현 → 제목키워드 → 본문동사 순서 (순서 중요)
- 키워드 사전은 코드에 (정규식 혼재 · 게임별 차이 없음 · 약 60개)

### 회원
```sql
app_user(app_user_id BIGSERIAL PK, login_type LOCAL|STEAM,
         email·password_hash (LOCAL만), steam_id (STEAM만), nickname, status,
         UNIQUE(email), UNIQUE(steam_id),
         CHECK (LOCAL이면 email+password / STEAM이면 steam_id))
```
스팀 OpenID 는 SteamID64 하나만 줍니다 (이메일·닉네임 없음). PostgreSQL UNIQUE 는 NULL 다중 허용.

### 목업에서 확정한 것
- **유사 사례 검색은 패치 단위** (`cases[]` 에 변경점별 분해 없음) → 임베딩은 노트 전체 단위. 75만 행 · 1.1GB
- **목업은 슬롯 2개만 사용** (`cat` `player_impact`). `target_side` `scope` 는 0회 등장
  → 슬롯 5개 → **3개** (`change_type` `direction` `player_impact`)
  → `target_side` 를 빼면 **`game_term` 테이블이 불필요** (테이블 20 → 19)
- 유사 사례 매칭 1차 기준은 장르(태그) + `player_impact` ("ARPG · 초안과 player_impact 축이 일치하는 사례")

---

## 6. 미결정 — 팀 회의 안건

노션 「7 · 정해야 할 것」 토글에 상세히 있습니다.

### A · 지금 막고 있는 것
| 항목 | 선택지 | 막고 있는 것 |
|---|---|---|
| **A-1 JPA vs MyBatis** | JPA면 전부 대리키 / MyBatis면 자연키 허용 | PK 전략 전체 |
| **A-2 `code` 테이블 구조** | ① 공통 1개 ② 슬롯별 3개 ③ **1개+생성컬럼(안전, 권고)** | `patch_change` DDL 확정 |
| A-3 임베딩 단위 | **목업으로 해결 — 노트 전체** | (해결) |
| A-4 슬롯 값 목록 | 슬롯은 3개로 확정. **각 슬롯에 어떤 값이 들어가는지 미정** | `code` 테이블 초기 데이터 |
| **A-5 임베딩 차원** | `patch_change.embedding vector(768)` 의 768 이 임시값 | 모델 정하면 확정. AI 담당 |
| **A-6 패치 정규화 단위** | 공지 1건=1행 (현재) vs 문장 단위 | 팀원이 테스트 중. 문장 단위면 `patch_change` PK 가 `patch_change_id BIGSERIAL` 로 바뀌고 `news`→`patch_change` 가 1:1 에서 1:N 이 된다. **다른 17개 테이블은 영향 없음** |

A-2 ③안:
```sql
change_type       VARCHAR(30),
change_type_group VARCHAR(30) GENERATED ALWAYS AS ('CHANGE_TYPE') STORED,
FOREIGN KEY (change_type_group, change_type) REFERENCES code (code_group, code_value)
```
PostgreSQL 12+ 필요. ①안으로 시작해도 데이터 이관 없이 전환 가능.

### B · 곧 필요
PostgreSQL 버전 / `language` 테이블 유지 여부 / `user_auth` 분리 여부 / 게임 카탈로그 갱신 주기와 신규 게임 편입 / 리뷰 수집 대상 기준(1,000건) 확정 / 공지 재수집 주기 / 배치 실패 재수행 정책 / Plan 탭 입력 저장 여부

### C · 구현 단계
유사 검색 순위 규칙 / 긍정률 표시 최소 표본 (5건 + n 병기 권고) / 배치 재시도 정책 /
`news.patch_reason` 유지 여부 (삭제 후보)

`game_term` 채우는 법과 벡터 인덱스 종류는 해결됐다 — 테이블이 없어졌고, 인덱스는
`hnsw` 로 확정했다 (74만 행 · 벡터 1.06GB 규모면 `ivfflat` 이 불필요).

### D · 실측 — 1개만 남음
임베딩 처리 속도 (AI 담당). 나머지 3개는 완료.

### E · 문서가 없는 것
**사용자 Flow · 화면 간 연결** (컨설턴트 피드백 7번). 탭 사이 이동이 정리 안 됨.
알려진 이슈: Daily 에서 3개월 전 패치를 보다 Reviews 로 넘어가면 갑자기 최신 패치가 나옴. 의도인지 확인 필요.

### 결정된 것 (다시 논의 불필요)
- 조사 템플릿 6개 페이지 → **작성하지 않기로 함.** 조사 내용은 「2 · 수집」에 실측값으로 이미 정리됨
- 테이블 18개 · 관계 22개 · 전체 DDL → `docs/db/schema.sql`
- 인프라 전부 (아래 11장)

### ERD 작업 시 주의
`docs/db/schema.sql` 과 `docs/db/schema.html` 이 최신이다. 노션 「데이터 정리」 페이지의
오래된 서술과 어긋나면 **DDL 쪽이 맞다.**

ERDCloud 에 그리기 전에 A-1(JPA vs MyBatis)과 A-2(`code` 구조)를 정해야 한다.
둘 다 PK 와 FK 모양을 바꾼다. A-6 은 `patch_change` 하나만 바꾸므로 나중에 반영해도 된다.

---

## 7. 도구 사용 시 함정

### Notion MCP (`notion-update-page` / `update_content`)
1. **한글을 유니코드 이스케이프로 손으로 쓰면 오타가 납니다.** 실제로 25건 이상 발생했습니다 (`쉼표`→`쓼표`, `탭`→`항`, `최신`→`최슬`, `비슷`→`뱄슷`).
   → 페이지를 fetch 한 파일에서 문자열을 슬라이스해 `json.dumps(..., ensure_ascii=True)` 로 생성하세요.
2. **긴 다중행 `old_str` 은 자주 실패합니다.** 짧은 단일행 앵커를 쓰세요.
3. **들여쓰기(탭)를 정확히 맞춰야 합니다.** 토글 내부는 `\t` 로 시작합니다. 에러 메시지가 "different indentation" 이면 이것입니다.
4. **`new_str` 에 코드 펜스(```)를 넣을 때는 `old_str` 이 펜스 짝을 완전히 포함해야 합니다.** 안 그러면 원래 닫는 펜스가 고아가 되어 문서가 ```javascript 블록으로 깨집니다. 실제로 5곳이 깨졌습니다.
   → 가능하면 표와 문장으로 쓰고 펜스를 피하세요.
5. `replaceAllMatches` 파라미터는 이 버전에서 받아들여지지 않습니다. 중복 매치는 문맥을 붙여 유일하게 만드세요.
6. 페이지가 65KB를 넘어 `notion-fetch` 결과가 파일로 저장됩니다. 그 파일에서 리터럴 `\n`(백슬래시+n)을 실제 개행으로 바꿔 읽으세요.

### Bash 도구
- **heredoc 안의 `\\n` 이 붕괴됩니다.** `chr(10)`, `chr(9)`, `chr(92)` 를 쓰세요.
- 모든 Python 스크립트 첫머리에 필수:
  ```python
  sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
  ```
- PowerShell 에서 `&&` 는 파서 오류입니다. `;` 를 쓰세요.

### Spark on Windows
```
① read.json 에서 JVM 이 조용히 죽음
   → Java 17 모듈 제한. --add-opens=java.base/{java.lang,java.lang.invoke,java.io,
     java.net,java.nio,java.util,java.util.concurrent,sun.nio.ch,sun.nio.cs,
     sun.security.action,jdk.internal.misc}=ALL-UNNAMED  (11개)
② 디렉터리·glob 경로에서 JVM 이 죽음
   → winutils 없이 Hadoop 이 파일 목록을 못 만듦.
     파이썬 glob 으로 파일 목록을 만들어 file:/// URI 리스트로 전달
③ Parquet 쓰기 실패 (HADOOP_HOME unset)
   → winutils.exe 필요. 출처 불명 서드파티 바이너리라 받지 않았음.
     그래서 Spark 측정은 JSONL 기준 = 상한값
```
리눅스 서버에서는 ①만 해당됩니다.

### 도구 재실행
```bash
# 지속 수집 (8시간, 동시성 램프)
cd C:\Users\SSAFY\Desktop\S15P21A202\scripts; python collect_soak.py --hours 8 --ramp 8:60,16:60,32:90,48:0

# Spark 집계 벤치마크 (코어별로 프로세스 분리 필요 — 같은 프로세스에서 세션 재생성하면 JVM 죽음)
cd C:\Users\SSAFY\Desktop\S15P21A202\scripts; python spark_bench.py agg --parallel 8 --source json
```
8시간 실행 전 절전 해제 필수: `powercfg /change standby-timeout-ac 0` (복구는 `30`).
이 노트북은 Modern Standby 라 뚜껑을 닫으면 CPU 가 조절돼 측정이 오염됩니다.

---

## 8. 작업 방식 (사용자 선호)

- **실측 없는 주장을 받지 않습니다.** "실측해볼래?", "가능한거임?" 이라고 되묻습니다. 재현 안 되는 수치는 폐기합니다.
- 문서 순서를 지킵니다: **필요한 데이터 → 수집 → 가공 → 저장.** 단계를 섞으면 지적합니다.
- 노션에 직접 쓰는 것을 선호합니다 (마크다운을 건네받아 붙여넣는 것 말고).
- 결론을 먼저, 근거를 뒤에. 장황한 선택지 나열을 싫어합니다.
- 한국어로 소통합니다.

---

## 9. 다음에 할 일

1. **팀 회의** — A-1(JPA/MyBatis) · A-2(`code` 구조) 결정
2. 결정 반영 후 ERDCloud 물리 ERD 작성
3. 슬롯 값 목록 확정 (3개로 축소된 것 기준)
4. 사용자 Flow 문서 작성 (컨설턴트 피드백 7번 · 미착수)
5. 임베딩 처리 속도 측정 (AI 담당)
6. 수집기 실제 구현 — **빈 페이지 4연속 규칙과 gzip 을 반드시 넣을 것**

---

## 10. 2026-09-06 추가 실측 — 증분 수집 전환

### filter=updated 가 핵심입니다
`appreviews` 의 `filter` 파라미터는 정렬 기준을 바꿉니다.

| filter | 정렬 기준 | 300건 검사 |
|---|---|---|
| `recent` | **작성일** 내림차순 | created 위반 0 / updated 위반 1 |
| `updated` | **수정일** 내림차순 | created 위반 68 / updated 위반 **0** |
| `all` | 작성일 내림차순 (불안정) | created 위반 44 / updated 위반 52 |

`filter=updated` 로 5게임 × 1,000건 검증: 수정일 내림차순 위반 0~1건.
**15년 전 작성된 리뷰의 수정도 앞으로 올라옵니다** (Terraria created 최고령 2011-10-25).

### 하루 증분은 전체의 0.03%
게임 15개 · 총 2,347만 건 중 24시간 내 갱신 6,806건 = **0.029%**.
전체 1.67억 환산 → **하루 약 4.8만 건 · 순수 전송 16초 · 실제 1~2분.**
교차 검증: 스팀 전체 1.5억을 13년으로 나누면 하루 3.2만 건. 신규 3.7만 추정과 일치.

### 그래서 하루 예산이 바뀌었습니다
```
최초 1회   수집 15.6h + 임베딩 4.6h = 20.2시간
매일       수집 1~2분 + 토픽 분류 수 초 + Spark 5분 + AI 요약 = 10분~1시간
```

### Query API 추가 확인
```
이어받기      start 오프셋. 커서/토큰 없음. sort=11 은 순서가 흔들려 이어받기 부적합
             sort=2 = appid 오름차순 (끝까지 유지, 페이지 겹침 0) → 이어받기는 이걸로
특정 게임     appid 지정 불가. filters.appids / include_apps / query.appids 모두 무시됨
             → 그래서 GetItems 가 따로 필요
appid 이진탐색  18콜 · 약 11초로 임의 appid 위치를 찾음. 오프셋이 아니라 appid 를 저장해야 함
             (total_matching_records 가 1시간 사이 183,980 ~ 184,606 으로 흔들림)
신규 게임      sort=2 맨 뒤 1,000개를 1콜로 받아 저장된 최대 appid 와 비교. 하루 약 55개 추가
tagid        언어와 무관하게 동일 (koreana/english 430개 tagid 집합 일치)
             492=인디/Indie · 1662=생존/Survival
```

### GetItems 용도 정정
문서에 "리뷰 수 보강" 이라 적혀 있던 것은 **틀렸습니다.**
Query 와 반환 필드가 완전히 동일하고(최상위 17개 · basic_info 차이 0), 리뷰 본문은 오지 않습니다.
실제 용도는 **게임 메타데이터**(이름 · 개발사 · 퍼블리셔 · 출시일 · 태그)이고, 필요한 이유는 **콜 수**입니다.
```
Query 전량 순회   185콜 · 311초   appid 지정 불가
GetItems         43콜            8,409개 지정 조회 (200개/콜)
```

### summary_unfiltered 는 조건부입니다
게임 17개 중 **4개(23.5%)에만** 옵니다 (STS2 · Cyberpunk 2077 · Warframe · Slay the Spire).
오프토픽 필터가 실제로 걸린 게임에만 옵니다.
→ 규칙: **unfiltered 가 오면 그것을, 없으면 filtered 를 쓴다.** 없다는 건 필터로 빠진 게 없다는 뜻.

### 스토어 리뷰 수는 스팀 구매자만 센 값
`purchase_type=steam` 과 오차 0.1% 이내로 일치. 우리는 `all` 로 받으니 **평균 18% 더** 받습니다.
```
Terraria        스토어 1,237,127 / steam 1,237,188 / all 1,552,714
Stardew Valley  스토어   892,552 / steam   891,949 / all 1,036,847
```

### daily_stat 행 수 산정 완료
게임 132개 표본을 규모별로 나눠 8,239개로 외삽: **약 3,425만 행 · 약 2GB.**
`display_review`(329MB)의 6배로 **스키마에서 가장 큰 테이블**입니다.

---

## 11. 인프라 — 2026-09-09 구축 완료

상세는 노션 INFRA 열 3개 페이지. 여기는 요약만 둔다.

### 확정된 구성
```
Hadoop 3.5.0 · Spark 4.2.0 · Java 17 · PostgreSQL 17.11 + pgvector 0.8.6
```

Spark 4.2.0 이 품은 `hadoop-client` 가 정확히 3.5.0 이라 클러스터와 버전이 일치한다.
Hadoop 3.5.0 은 Java 17 바이트코드(major=61)로 컴파일돼 있어 `--add-opens` 우회가 필요 없다.

| 노드 | 역할 |
|---|---|
| 노트북1 (마스터) | NameNode · SecondaryNameNode · ResourceManager · DataNode · NodeManager |
| 노트북2~5 (워커) | DataNode · NodeManager |
| 노트북6 | AI 전용 (RTX 4070). 클러스터 노드 아님 |
| 서버1 | 서비스 · PostgreSQL 17 |
| 서버2A | HDFS 백업 보관 |

EC2 2대는 **보안그룹이 22번만 열려 있어 노드로 쓰지 않는다.** SSH 터널로 PostgreSQL 에
적재하고 `scp` 로 백업만 넘긴다.

자원 합계 74GB · 30 vcore (마스터 10GB, 워커 16GB × 4). WSL2 는 호스트 63.5GB 중 31GB 만 본다.

### 마스터 검증 결과 (1노드 기준, 전부 통과)
| 항목 | 결과 |
|---|---|
| HDFS 86MB 왕복 | sha256 동일 · 블록 134MB · 복제 3 |
| YARN MapReduce pi | 3.135 · 16.2초 |
| Spark on YARN pi | 3.14192 |
| Spark → HDFS Parquet | 200만 행 · 긍정 1,333,333 (정확히 2/3) · appid 74,000 · 날짜 14일 |
| 대역 간 인바운드 | 무선 워커 → 유선 마스터 `9000` 도달 확인 |

### ⚠️ WSL2 에서 발목 잡은 것들 — 다른 노트북에서도 같이 겪는다
| 증상 | 원인 | 해결 |
|---|---|---|
| `apt`·`curl` 이 `No route to host` | 죽은 기본 게이트웨이를 WSL 이 골라옴. 1시간 수명으로 계속 재등록됨 | `0.0.0.0/1` + `128.0.0.0/1` 로 덮기. `infra/05-wsl-route-fix.sh` |
| 재시작하면 `UnknownHostException: dispatch-master` | WSL 이 부팅마다 `/etc/hosts` 를 새로 만듦 | `wsl.conf` 에 `generateHosts = false` |
| 터미널 닫으면 노드가 사라짐 | WSL2 유휴 타이머가 VM 을 내림 | `vmIdleTimeout=-1` + 시작프로그램. `infra/09-wsl-keepalive.ps1` |
| 다른 노드가 못 붙음 | Hyper-V 방화벽 `DefaultInboundAction = Block`. **Windows "허용" 팝업이 안 뜬다** | `infra/06-firewall.ps1` (관리자) |
| 데몬이 셸 종료 시 죽음 | `start-dfs.sh` 로 띄우면 SIGHUP | systemd 서비스화. `infra/08-systemd-hadoop.sh` |
| Spark UI 가 `10.255.255.254` | 미러링 모드 `lo` 주소를 자기 주소로 잡음 | `spark-env.sh` 에 `SPARK_LOCAL_IP` 명시 |

### 마스터 IP 는 고정이 아니다
무선 → USB 유선으로 바꾸자 `70.12.246.60/21` → `70.12.108.81/24` 로 서브넷까지 달라졌다.
그래서 설정 XML 에는 IP 를 쓰지 않고 `dispatch-master` 이름만 쓴다. 바뀌면 각 노드에서:

```bash
bash infra/07-cluster.sh setmaster <새 IP>
sudo systemctl restart dispatch-cluster.target
```

### 스키마에 직접 걸리는 실측 하나
`partitionBy` 로 쓴 BOOLEAN 컬럼은 읽을 때 **STRING 으로 돌아온다.** 파티션 값이
디렉터리명(`voted_up=true`)에서 복원되기 때문이다.

```
partitionBy("voted_up")  ->  struct<..., voted_up:string>
일반 컬럼                 ->  struct<..., voted_up:boolean>
```

**HDFS 파티션은 날짜(`stat_date`)로만 잡고 `voted_up` 은 일반 컬럼으로 둔다.**

### 남은 일
- 워커 4대 설치 — IP 는 등록해둠 (`70.12.246.76` `70.12.247.106` `70.12.247.103` `70.12.247.164`)
- 노트북↔노트북 실제 처리량 측정 → 22GB 적재 시간 확정 (지금은 추정 40분~1시간)
- 마스터 → 서버1 PostgreSQL SSH 터널 자동화
- 서버2A 백업 스크립트 (HDFS 데이터 + NameNode 메타데이터)
- 오전 9시 배치 systemd 타이머
- SSAFY 포트 개방 요청 — 배포용 `80` `443`
