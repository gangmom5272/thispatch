# AI 노드 ↔ HDFS 파일 계약 (초안 v1, 2026-09-11)

`common/HdfsPaths.java`의 `/embeddings`, `/review_topic` 두 자리("모양은 아직 정해지지 않았다")를 채우는 문서다.
컬럼명은 **DB 이름(`backend/.../V1__init.sql`, `V2__add_patch_analysis.sql`)을 그대로** 따른다.
게임 식별자는 Parquet·DB 에서 `appid`(백엔드 API 계약 `conventions.md`: API 는 `gameId`, DB 는 `appid`). AI 요청 API 도 백엔드와 맞춰 `gameId` 를 받으면 내부에서 `appid` 로 다룬다.
값은 받은 그대로 두고 타입 변환은 적재 단계에서 한다(`ReviewSchema` 규칙과 동일).

## 1. 누가 무엇을 읽고 쓰나

```
/news_raw/dt=D (Spark 변환·판정 완료) ──▶ 후속 처리
                                                    ──▶ /embeddings/patch_chunk/dt=D
                                                    ──▶ /embeddings/patch_change/dt=D
/review_raw/{base,delta/dt=D}  (Spark)  ──▶ AI 노드 ──▶ /review_topic/dt=D
```

- AI 노드는 **읽기 전용**으로 입력을 가져가고, 자기 폴더에만 쓴다. PostgreSQL에 직접 쓰지 않는다.
- 연결은 파일이다. HTTP 호출 없음. AI 노드는 입력 폴더에 `_SUCCESS`가 있을 때만 읽고, 출력 폴더에 다 쓴 뒤 `_SUCCESS`를 만든다. Loader(Spring Batch)는 `_SUCCESS`가 있는 폴더만 적재한다.
- `dt`는 KST 날짜. 같은 `dt`를 다시 돌리면 출력 폴더를 통째로 덮어쓴다(부분 갱신 없음).

## 2. 입력 (AI 노드가 기대하는 것)

| 경로 | 형식 | 필요한 컬럼 | 비고 |
|---|---|---|---|
| `/news_raw/dt=D/` | Parquet | `gid` string, `appid` long, `title`, `contents`(BBCode 원문), `feed_tags` string(쉼표 연결), `published_ts` long(unix초), `collected_ts` long | `NewsSchema.NEWS_RAW`는 원문 필드와 `is_patch` BOOLEAN, `patch_reason` STRING을 포함한다. `NewsToParquet`가 공통 PatchClassifier를 호출한다. 최신 수집본은 `NewsLake.latest` 기준. 판정·패치 리뷰 집계에 AI는 관여하지 않는다. 부록 A 참고. |
| `/review_raw/base/`, `/review_raw/delta/dt=D/` | Parquet | `recommendationid` long, `appid` long, `review_text`, `language_code`, `created_ts` long, `updated_ts` long | `ReviewSchema.REVIEW_RAW` 그대로. 초기 1회는 base 전체, 이후 delta만. **같은 `recommendationid` 가 여러 판 있을 수 있음**(수정본·재수집, `common/ReviewLake` 주석 참고) → AI 노드는 `updated_ts` 최신 한 벌에만 토픽을 붙인다 |

> 제안: 공지 원문 → 청크 분리 → 규칙 슬롯(방향·변경 유형)은 **AI 노드(Python)** 가 한다. 코드가 이미 있고(동료 `sections.py`, PoC 규칙), Spark 쪽은 아직 리뷰 변환만 있다. Spark 담당이 원하면 옮길 수 있게 청크 출력 컬럼을 아래처럼 고정한다.

## 3. 출력

### 3-1. `/embeddings/patch_chunk/dt=D/*.parquet` → 테이블 `patch_chunk`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `gid` | string | 공지 ID (FK news) |
| `seq` | int16 | 공지 안 순번. `(gid, seq)`가 자연키 |
| `text` | string | 임베딩 입력 문자열 그대로. `제목 \| Context: 소제목 \n Change: 본문` |
| `extraction_status` | string | `succeeded` / `failed` |
| `embedding_status` | string | `succeeded` / `failed` / `skipped` / `duplicate`. **변경점이 없는 청크(소제목·인사말·설명)는 `skipped`**, **같은 게임이 공지마다 반복하는 동일 문장은 첫 1개만 `succeeded`, 나머지 `duplicate`** 로 저장만 하고 임베딩하지 않는다 → HNSW 부분 인덱스(`WHERE embedding_status='succeeded'`)에서 자동 제외. 화면의 공지 원문 표시는 status 와 무관하게 전 행을 쓴다 |
| `embedding` | list<float32>[512] | `succeeded`가 아니면 null |
| `embedding_model` | string | `embeddinggemma-300m-bf16-512` |
| `model_version` | string | `rule-v1`(규칙만) 또는 `qwen3.5-9b-q4km`(Qwen 백필이 덮어쓴 청크). Qwen은 `skipped` 청크도 대상으로 삼아 규칙이 놓친 변경점을 되살릴 수 있음 |
| `processed_at` | long | unix초 |

`chunk_id`는 DB가 부여한다. Loader는 `(gid, seq)`로 upsert.

### 3-2. `/embeddings/patch_change/dt=D/*.parquet` → 테이블 `patch_change`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `gid`, `seq` | string, int16 | 어느 청크의 변경점인지. Loader가 `patch_chunk.chunk_id`로 바꿈 |
| `change_seq` | int16 | 청크 안 변경점 순번 |
| `change_type` | string | 코드 문자열 `add/remove/modify/fix/deprecate`. Loader가 `change_type_id`로 변환 |
| `direction` | string | `increase/decrease/none/not_applicable/unknown` |
| `target_type` | string | `player/…/unknown` |
| `target` | string | 대상 이름 (Axebot). **ERD 확정 전, 컬럼 미채택 시 Loader가 무시** |
| `attribute` | string | 속성 (health). 같음 |
| `evidence_quote` | string | 근거 문장 원문 |
| `validation_status` | string | `valid/needs_review/rejected` |
| `model_version` | string | 이 행을 만든 쪽. `rule-v2`(규칙) 또는 `qwen3.5-9b-q4km/service-facts-2`(Qwen). 9/18 추가 |

**DB `patch_change` 를 채우는 규칙 (9/18 합의, 4-2 보완):**

```
기본     Spark PatchChangeExtractor 산출 → 전 청크          (4-2 정본)
덮어쓰기 이 파일에서 model_version LIKE 'qwen%' AND validation_status = 'valid' 인 행만
         → 같은 (gid, seq) 의 기존 행 삭제 후 재삽입
```

- `model_version = 'rule-v2'` 행은 **DB 에 넣지 않는다.** Spark 규칙과 같은 성질의 값이라 넣을 이유가 없다.
- Qwen 행은 시간 예산 안에서 인기 게임·최근 공지부터 며칠에 걸쳐 늘어난다. 적재기는 매일 다시 돌 수 있어야 한다.
- 규칙 값 위에 Qwen 값이 덮이는 이유는 부록 B. Qwen 이 채우는 `target`·`attribute` 가 화면 05(사례 비교)의 알맹이다.

### 3-3. `/review_topic/dt=D/*.parquet` → 테이블 `review_topic`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `recommendationid` | long | Loader가 `recent_review.review_id`로 바꿈 |
| `appid` | long | 조인 보조 |
| `topic_id` | int16 | `topic` 테이블 ID. **리뷰 하나에 여러 행** 가능(다중 라벨). AI 쪽 고정값: balance=1, bug=2, ui=3, ops=4, bm=5 → `topic` 시드를 이 순서로 |
| `score` | float32 | 분류기 확률. DB에는 안 넣어도 됨 |

토픽이 하나도 안 붙은 리뷰는 행이 없다. **파일에는 전 언어를 분류해 담고, DB 에는 검증된 8개 언어만 넣는다** (9/19 결정):

```
english · koreana · schinese · russian · japanese · german · french · spanish
```

- 영어·한국어는 사람 라벨 490건으로 검증(micro-F1 0.56). 나머지 6개는 9/19 GMS gpt-4.1 라벨 80건/언어 교차 검증에서 같은 심판 기준 영·한(0.47)과 동급(중 0.52 · 일 0.50 · 독 0.50 · 러 0.50 · 프 0.42 · 스 0.42)이었다. 터키어 0.35 · 브라질 포르투갈어 0.37 은 제외.
- 9/14 의 "러시아어 2%·중국어 42%" 는 v1 분류기 기준이라 더 쓰지 않는다.
- 알려진 편향: 러시아어·중국어는 balance 를 과다하게 붙인다(정밀도 0.5~0.6, 재현율 0.8~0.9).
- **Loader 는 `recent_review.language_code` 가 위 8개일 때만 `review_topic` 을 넣는다.** 언어를 더하거나 빼는 것은 이 목록만 바꾸면 된다(AI 재실행 불필요).
- **같은 리뷰는 dt 파일들 전체에서 한 번만 나온다.** 초기 수집이 dt 조각으로 나뉘어 같은 리뷰가 여러 dt 에 있었는데(09-18 은 09-14·16 과 34% 겹침), AI 가 앞 dt 에 남기고 뒤 dt 에서 제거했다(9/20). `(recommendationid, topic_id)` 는 파일 전체에서 유일하므로 PK 충돌 없이 넣을 수 있다. 다만 Loader 는 안전하게 upsert(`ON CONFLICT DO NOTHING`) 를 권장한다 — 일일 배치에서 수정된 리뷰가 다시 올 수 있다.
- 화면 02 토픽 비율의 분모는 '8개 언어 · 30바이트 초과' 리뷰 수로 표기한다.

### 3-4. 언제 읽어야 하나 (Loader 쪽에서 볼 것)

2026-09-17 기준 `/embeddings`·`/review_topic` 을 읽는 코드는 아직 없다(`HdfsPaths` 에 상수만 있다).
만들 때 아래 세 가지를 반영해 주면 좋겠다. 셋 다 AI 쪽 배치 구조에서 나오는 성질이다.

**① `_SUCCESS` 가 있는 날짜 폴더만 읽는다.**

`dt=D` 폴더는 쓰는 중에도 보인다. Spark·AI 모두 다 쓴 뒤 마지막에 0바이트 `_SUCCESS` 를 만든다.
없으면 아직 쓰는 중이므로 읽으면 일부만 가져간다. AI 배치도 입력을 읽을 때 같은 검사를 한다
(`run_daily.sh` 의 `need_success`).

**② 같은 날짜 폴더가 하룻밤에 두 번 쓰인다.**

```
3단계  put ①   patch_chunk · patch_change · review_topic   (규칙 변경점 판본)
4단계  rm → put ②   patch_chunk · patch_change            (Qwen 덮어쓴 판본, 몇 시간 뒤)
```

임베딩·토픽은 분 단위로 끝나지만 Qwen 백필은 시간 단위다(청크당 1.2~1.7초, 기본 예산 4시간).
Qwen 을 기다리면 완성된 임베딩·토픽도 아침까지 DB 에 못 들어가므로 먼저 한 번 올린다.

- `review_topic` 은 ① 에서 끝이다. 다시 쓰지 않는다.
- `patch_chunk`·`patch_change` 는 ② 에서 다시 쓰인다. **① 만 읽으면 Qwen 결과가 빠진 데이터가 들어간다.**
- 구분은 `patch_chunk.model_version` 으로 한다. `rule-v2` 는 규칙 판본,
  `qwen3.5-9b-q4km/service-facts-2` 는 Qwen 판본이다.
- Qwen 은 시간 예산 안에서 최근 공지부터 처리하므로, ② 이후에도 일부 청크는 규칙 판본으로 남는다.
  이는 정상이며 다음 날 이어서 채운다(`state/qwen_done.jsonl` 로 중복을 피한다).

**③ `put` 은 날짜 폴더 단위로 지우고 다시 넣는다.**

HDFS `put` 은 이미 있는 폴더에 덮어쓰지 못해 실패한다. 그래서 그 날짜 폴더만 지우고 넣는다.

```
rm -r -f  /embeddings/patch_chunk/dt=D     ← 그 날짜만
put       out/embeddings/patch_chunk/dt=D
```

다른 날짜와 다른 경로는 건드리지 않는다. 다만 **지우고 넣는 사이에 그 날짜가 잠깐 사라진다.**
그 순간에 읽으면 빈 결과가 된다 — ① 의 `_SUCCESS` 검사로 걸러진다.

## 4. 정해야 남은 것 (상대 확인 필요)

1. **합의 완료:** `/news_raw`에 `is_patch`, `patch_reason`을 저장한다. 판정은 Spark `PatchClassifier`, DB 적재는 -25 담당이다. 판정·패치 리뷰 집계는 AI와 연결하지 않는다.
2. **합의 완료(9/16, 9/18 보완):** 변경점 추출 정본은 Spark `PatchChangeExtractor`(`change-rules-2`)다. AI `rules.py`(`rule-v2`)는 DB `patch_change` 의 출처가 아니다. **단 Qwen 판본 행(`model_version LIKE 'qwen%'`, `valid`)은 같은 `(gid, seq)` 를 덮어쓴다** — 3-2 의 채우기 규칙 참고. 구역 분리는 Spark `PatchChangeSectioner.split` 이 소제목 기준으로 하고, **토큰 한도에 맞춘 최종 청크 생성과 임베딩은 AI 노드**가 한다([PATCH_PROCESSING.md](../spark/PATCH_PROCESSING.md) 명시). 대조 결과는 부록 C 참고.
3. `target`, `attribute` 컬럼 채택 여부 — ERD 담당 (미채택이면 Loader가 두 컬럼만 버림, AI 쪽 변경 없음)
4. 초기 적재 시 `review_raw/base` 전체를 토픽 분류할지, 패치 창(전후 7일) 안 리뷰만 할지 — 백엔드. 기본값: 전체

## 5. 실행 위치

- 개발·검증: Windows py313 + Ollama (지금 환경). HDFS 대신 로컬 폴더에 같은 구조로 입출력.
- 운영: WSL (HDFS 클라이언트 있음). HDFS 주소는 `hdfs://thispatch-master:9000`(`common/HdfsPaths.java`). `git pull` + `pip install -r ai/requirements.txt` 후 `batch/run_daily.sh`가 `hdfs dfs -get` → 처리 → `hdfs dfs -put`.
- 경로·Ollama 주소는 전부 환경 변수(`AI_WORK_DIR`, `OLLAMA_URL`). 코드에 절대 경로 없음.


## 부록 A. 패치 판정 정본과 전달 상태

2026-09-16 합의: 판정은 [PatchClassifier.java](../spark/src/main/java/com/ssafy/thispatch/spark/PatchClassifier.java)
하나로 통일한다. 규칙 버전은 `patch-rules-5`이며 이전 부록 A의 PoC 규칙은 사용하지 않는다.

`NewsToParquet` → `PatchClassificationProcessor.classifyRows` → `PatchClassifier` 순서로 호출한다.
원문 13개 필드에 다음 2개를 추가해 `/news_raw/dt=D` Parquet에 저장한다. 판정으로 행을 제외하지 않는다.

| 상태 | is_patch (BOOLEAN) | patch_reason (STRING) |
|---|---|---|
| PATCH 확정 | true | 정본 함수의 사유 코드 |
| NOT_PATCH | false | 제외 사유 코드 |
| REVIEW_REQUIRED | false | 근거 부족·충돌 사유 코드 |
| 판정 전 | false | `0:unjudged` (예약값) |

`0:unjudged`는 적재 전 임시 상태이며 판정 함수의 실제 반환 사유와 겹치지 않는다.
근거 부족은 미판별이 아니다. 실제 판정 사유는 대문자 코드 그대로 저장한다.

```java
PatchClassifier.Result result = PatchClassifier.classify(title, contents, tags);
boolean isPatch = result.isPatch();
String patchReason = result.reason();
```

서명은 `public static Result classify(String title, String contents, List<String> tags)`다.
원본 tags 배열을 전달한다. Dataset 처리기는 쉼표 연결 feed_tags를 목록으로 바꿔 호출한다.

- 원본 JSON을 보존하고 재변환 시 현재 규칙으로 판정한다. 별도 PatchJudge/PoC 규칙을 연결하지 않는다.
- 패치 집계는 저장된 `is_patch=true`만 사용한다. `patch_reason`을 다시 판정하지 않는다.
- 패치 결정일은 `published_ts`의 KST 날짜다. `patched_at`은 게시 시각을 보존한다.
- 판정·패치 리뷰 집계에 AI는 관여하지 않는다. 별도의 AI 임베딩·분석 계약은 이번 연결 범위 밖이다.
- DB 재적재·기존 판정 보호는 -25 담당이며 이 변경에서 DB 스키마·마이그레이션·적재를 수정하거나 실행하지 않았다.
- 운영 재변환·배포는 아직 실행하지 않았다. 상세 입력 검증과 진단 필드는 [판정 문서](../spark/PATCH_CLASSIFICATION.md)를 따른다.


## 부록 B. 규칙 슬롯과 Qwen 의 관계 (검토 요청 회신)

- `patch_change` 의 정규화 값은 **Qwen 출력이 최종**이다. 규칙(`rules.py`)은 같은 컬럼을 먼저 채우는 임시값이며 `model_version` 으로 구분한다(`rule-v2` → `qwen3.5-9b-q4km/...`). DB 에서는 임시값 자리를 Spark 산출이 맡고, Qwen 행만 그 위에 덮인다(3-2, 4-2).
- 규칙이 남는 이유 세 가지: ① 변경 문장 청크만 골라 Qwen 호출을 1/3 로 줄임(10,428 → 7,074) ② Qwen 이 그날 못 돈 공지도 방향·변경 유형은 당일 채워 검색 후보에서 빠지지 않게 ③ Qwen 결과가 근거 불일치(needs_review, 테스트 24%)일 때 대체값.
- 규칙이 하지 않는 것: 대상 이름·속성·조건(Qwen 전용). 대상 종류는 규칙 50~62% 라 Qwen 이 덮어쓰면 끝.


## 부록 C. 규칙 대조 (9/16 실측)

같은 공지 1,314건을 같은 청크 33,547개로 잘라 양쪽 규칙을 돌린 결과다.
입력이 같으므로 차이는 규칙에서만 온다.

| | Spark `change-rules-2` | AI `rule-v2` |
|---|---|---|
| 뽑은 변경점 | 18,118 | 31,784 |
| 변경점을 찾은 청크 | 44% | 58% |
| 변경 유형 | fix 47% · modify 38% · add 13% | fix 35% · modify 35% · add 24% |
| 방향 | not_applicable 62% · unknown 23% | not_applicable 35% · none 30% · increase 19% |
| 대상 종류 | **unknown 98%** | unknown 34% · system 18% · player 14% |

청크 단위로 "변경점이 있다/없다" 판정은 75% 일치한다.
둘 다 찾은 청크 13,016개에서 첫 변경점의 **유형 91% · 방향 72% · 대상 31%** 가 같다.

**Spark 가 더 정확한 곳** — 우리 규칙이 놓치는 걸러내기다. 화자 주어(`we released ...`),
예정 구역(`Upcoming changes`), 홍보 문구(merch·newsletter), 부정문을 제외한다.
`Last week we released new weapon collections` 를 우리는 `add` 로 잡지만 Spark 는 버린다.

**AI 규칙이 더 넓은 곳** — 동사 목록과 방향 판정이다. Spark 는 `lowered`, `raised`,
`buffed`, `nerfed`, `tweaked`, `rebalanced`, `restored`, `reverted`, `renamed` 와
명사형 `fix`(`Clipping fix to prevent ...`, `Speculative fix for ...`)를 잡지 않는다.
숫자만으로 방향을 읽는 규칙(`from 79 to 70` → decrease)도 없어 방향 unknown 이 23% 다.

**대상 종류는 사실상 비어 있다.** Spark 는 대상 구절의 맨 앞이 명시적 명사일 때만 종류를
정해 98% 가 unknown 이다. 의도된 보수성이지만(PATCH_PROCESSING.md), 화면에 표시할 값이
남지 않는다. Qwen 백필이 `target`·`attribute` 를 채우므로 운영에는 문제가 없고,
Qwen 이 못 돈 공지에서만 빈칸으로 남는다.

정리하면 Spark 는 **정밀도**, AI 규칙은 **재현율** 이 높다. 정본은 Spark 로 하되
위 동사·숫자 방향 두 가지를 보완하면 우리 규칙의 이점이 대부분 흡수된다.
