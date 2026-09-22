# patch API

패키지: `com.ssafy.thispatch.domain.patch` · [공통 규칙](conventions.md)

## 기획안 단계별 저장 정책

- 구조화 성공 시 회원·대상 게임·원문·최초 엔티티·최초 슬롯·해석·경고를 저장하고 `planId`를 반환한다. 프론트는 최초 구조화 결과를 검색 요청으로 재전송하지 않는다.
- 구조화 결과는 편집 화면에서 바로 표시한다. 아직 한 번도 검색에 성공하지 않은 기획안은 DB에 유지하되 마이페이지 기획안 내역 목록·상세에는 노출하지 않는다. 이미 완료된 내역은 후속 재검색 실패와 관계없이 계속 조회할 수 있다.
- 검색 요청은 `planId`, 사용자가 선택한 `genreIds`, 수정·추가한 `confirmedSlots`를 전달한다. 서버는 현재 회원 소유 여부와 기획안의 대상 게임이 경로의 `gameId`와 일치하는지 확인한다.
- 유사 사례 검색과 응답 생성에 필요한 AI 처리가 성공한 뒤 선택 장르·최종 확정 슬롯을 저장한다. 검색 결과가 0건인 경우도 성공한 검색 내역으로 저장하고 마이페이지에 노출한다.
- AI·검색 실패 시 선택 장르·최종 확정 슬롯을 저장하지 않으며, 이미 저장한 최초 구조화 정보와 이전 검색 내역을 보존한다. 프론트는 같은 기획안으로 검색을 재시도할 수 있다.
- 같은 기획안으로 다시 검색해도 이전 검색의 장르·확정 슬롯을 덮어쓰지 않는다. 별도 내역을 생성하고 최초 구조화 정보를 보존한다.
- 외부 AI 호출 중 DB 트랜잭션을 유지하지 않는다. 구조화 결과의 4개 테이블 저장은 하나의 트랜잭션으로 처리하며, 검색 단계의 장르·확정 슬롯과 검색 완료 상태 변경도 하나의 트랜잭션으로 처리한다. DB 저장 실패 시 해당 단계의 변경 전체를 롤백하고 성공 응답을 반환하지 않는다.
- 과거 유사 사례 검색 결과·통계·비교 정보 및 응답 전체 JSON은 저장하지 않는다. `restatement.highlights`도 별도로 저장하지 않는다.

위 단계별 정책은 기존 S15P21A202-269의 “구조화만 한 기획안은 저장하지 않는다”는 정책을 대체한다.
계약·DB 준비는 S15P21A202-283, 구조화 저장 연동은 S15P21A202-284, 검색 확정 저장 연동은 S15P21A202-285에서 수행한다.
283 완료 시 POST의 `planId` 입출력·저장 연동은 후속 구현 대상이며, 아래 계약을 기준으로 284·285에서 연결한다.

### 저장 단계와 시각

| 상태 | `patch_plan.structured_at` | `patch_plan.created_at` | 마이페이지 목록·상세 |
|---|---|---|---|
| 최초 구조화 저장 완료 | 구조화 결과 저장 시각 | `NULL` | 미노출 |
| 첫 검색 성공·확정 저장 완료 | 최초 구조화 시각 유지 | 검색 내역 저장 시각 | 노출 |
| 기존 내역으로 재검색 성공 | 원본 내역의 구조화 시각 복사 | 새 검색 내역 저장 시각 | 새 `planId`로 별도 노출 |
| V14 이전의 기존 내역 | `NULL` (실제 구조화 시각 불명) | 기존 값 유지 | 기존처럼 노출 |

- 두 시각은 `TIMESTAMPTZ`다. `created_at`은 기존과 동일하게 **검색 성공 후 확정 정보를 저장한 시각**이며, 요청 수신·구조화 시작 시각으로 바꾸지 않는다. 목록·상세의 `createdAt`과 정렬·커서는 계속 이 값을 사용한다.
- 신규 구조화 저장 시 `structured_at`과 `patch_plan_restatement.created_at`에는 같은 저장 시각을 기록한다. 이 단계에는 선택 장르와 최종 확정 슬롯을 저장하지 않는다. 구조화 응답의 `genreIds`는 현재 게임의 장르이며 검색 당시 선택값과 구분한다.
- 검색 완료는 `patch_plan.created_at IS NOT NULL`로 판정한다. 빈 장르 배열·검색 결과 0건·최초 구조화의 빈 결과로 완료 여부를 추론하지 않는다. 기존 내역의 확정 슬롯이 비어 있어도 기존 `created_at`이 있으면 조회 가능하다.
- V14는 `patch_plan.structured_at`을 nullable로 추가하고 `patch_plan.created_at`의 `NOT NULL`을 해제한다. 둘 다 `NULL`인 행은 CHECK 제약으로 거부한다. 기존 행의 시각을 추정하거나 덮어쓰지 않으며, 기존 V13 및 하위 5개 테이블은 변경하지 않는다.

### 첫 검색·재검색·통신 재시도

- 검색 전에 현재 회원 소유의 `planId`와 대상 게임을 확인한다. 미존재·타 회원 소유는 동일한 `404 PATCH_PLAN_NOT_FOUND`다. 본인 기획안의 `appid`와 경로의 `gameId` 불일치는 `400 INVALID_REQUEST`다. 검증에 실패하면 AI를 호출하거나 저장하지 않는다.
- 구조화만 완료한 기획안도 검색 대상으로 사용할 수 있다. 목록·상세의 미검색 내역 제외 조건을 검색 대상 조회에 적용하지 않는다.
- 검색 성공 후 저장 트랜잭션에서 원본 기획안 행을 잠그고 소유 여부·게임·검색 완료 여부를 재확인한다. 잠금은 외부 AI 호출이 끝난 뒤 짧은 저장 트랜잭션 안에서만 유지한다.
- `created_at IS NULL`이면 첫 검색이다. 요청의 `planId`에 장르·최종 슬롯을 저장하고 `created_at`을 기록한다. 원문·최초 엔티티·최초 슬롯·해석·경고·`structured_at`은 수정하지 않는다.
- `created_at IS NOT NULL`이면 재검색이다. 새 `patch_plan_id`를 생성하고 원본의 회원·게임·원문·최초 엔티티·최초 슬롯·해석·경고·`structured_at`을 복사한다. 선택 장르·최종 슬롯은 **이번 요청값**으로 저장하고 `created_at`은 이번 검색 내역 저장 시각으로 기록한다. 이전 내역의 확정 정보와 저장 시각은 수정하지 않는다.
- 재검색 복사 시 엔티티 PK를 새로 발급하고 최초 슬롯의 FK를 새 엔티티에 연결한다. 엔티티 순서와 기획안 전체 최초 슬롯 순서는 보존한다. 재진술의 `created_at`도 원본 값을 복사한다. 기존 내역에 재진술 행이 없으면 없는 상태를 유지하고, 원본의 `structured_at`이 `NULL`이면 그대로 복사한다. 최초 정보를 복원하려고 AI 구조화를 다시 호출하지 않는다.
- 원본 복사·장르·최종 슬롯·검색 시각은 한 트랜잭션에서 저장한다. 최종 슬롯들의 `created_at`은 해당 내역의 검색 저장 시각과 동일하게 기록한다. 저장 실패 시 첫 검색은 미확정 상태로 남고, 재검색은 새 내역과 하위 데이터 전체가 롤백된다.
- 같은 미확정 기획안의 검색 두 건이 동시에 성공하면 잠금을 먼저 획득한 요청이 첫 검색을 확정하고, 뒤 요청은 확정 상태를 확인한 뒤 새 내역으로 저장한다. 두 요청의 최종 슬롯을 섞거나 이전 내역을 덮어쓰지 않는다.
- `requestId`·멱등 키·요청 내용 비교는 추가하지 않는다. 구조화 요청 재전송은 새 기획안을 생성하며, 검색 요청 재전송은 위 첫 검색·재검색 규칙을 따른다. 성공 응답이 유실된 뒤 재시도하면 동일 내용의 내역이 여러 건 생길 수 있으며, 각 완료 내역을 목록에 표시한다.
- 검색 응답의 기존 필드·`201` 상태는 유지한다. 재검색으로 새로 저장된 내역의 ID는 목록의 `planId`로 조회하며, 검색 응답에 별도 ID 필드를 추가하지 않는다.

### 기존 6개 테이블과 저장 책임

| 테이블 | 구조화 저장 | 첫 검색·재검색 저장 및 제약 |
|---|---|---|
| `patch_plan` | 회원·게임·원문·`structured_at`, `created_at = NULL` | 첫 검색은 시각 확정, 재검색은 새 행. 회원·게임 FK와 기존 내역 보존 |
| `patch_plan_entity` | 이름·역할 기준 중복 제거, 순서, 대상별 `UNKNOWN_ENTITY` 경고 | 재검색에서 새 PK로 복사. `(patch_plan_id, entity_order)`의 기존 UNIQUE 유지 |
| `patch_plan_slot` | 최초 엔티티 FK·속성·유형·방향·변화량·범위·기획안 전체 순서 | 재검색에서 새 엔티티 FK로 복사. 엔티티별 UNIQUE를 넘어서는 기획안 전체 순서 정합성은 저장 서비스가 보장 |
| `patch_plan_restatement` | 최초 해석, 전체 `NO_CHANGES` 경고, 구조화 저장 시각 | 기획안당 최대 한 행. 재검색에서 원본 행이 있을 때만 복사 |
| `patch_plan_genre` | 저장하지 않음 | 이번 요청의 장르 ID 중복 제거 후 저장. 빈 배열은 0행(전체 장르). 기존 복합 PK·장르 FK 유지 |
| `patch_plan_confirmed_slot` | 저장하지 않음 | 이번 요청의 최종 슬롯을 순서대로 저장. 최초 엔티티와 독립적인 대상 수정·추가 허용 |

- 기존 코드 값 CHECK, FK의 `NO ACTION`, 순서 UNIQUE와 인덱스를 재사용한다. 원문·회원·게임 조합에 UNIQUE를 추가하거나 별도 확정 이력 테이블을 만들지 않는다.
- 빈 엔티티·슬롯·해석, 빈 대상 이름·속성, nullable 변화량·범위와 경고 없는 상태는 기존 계약을 유지한다. 단계별 저장 대상·전체 슬롯 순서·검색 확정의 원자성은 후속 저장 서비스가 보장하며, DB의 개별 FK·CHECK만으로 보장된다고 가정하지 않는다.

## 변경점 구조화

### `POST /games/{gameId}/plan-structures`

[변경점 구조화 Notion 명세](https://app.notion.com/p/e02776ebfd68835eb1d98107791e01cd)

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Request Body**

```json
{
  "text": "액스봇의 체력을 20% 올리고 공격력을 10% 올린다. 적용 범위는 난이도 '고통 4' 이상만, 레이스 계열 출현 빈도는 소폭 조정."
}
```

**Response 200**

```json
{
  "data": {
    "planId": 101,
    "gameId": 1091500,
    "rawText": "액스봇의 체력을 20% 올리고...",
    "genreIds": [
      1,
      2
    ],
    "entities": [
      {
        "id": 1,
        "name": "Axebot",
        "role": "ENEMY",
        "source": "AI",
        "editable": false
      }
    ],
    "slots": [
      {
        "id": 1,
        "targetName": "Axebot",
        "targetRole": "ENEMY",
        "attribute": "HP",
        "changeType": "MODIFY",
        "direction": "INCREASE",
        "magnitude": "+20%",
        "scope": "고통 4 이상",
        "editable": true
      }
    ],
    "restatement": {
      "text": "적(Axebot)의 체력·공격력을 상향하고, 적용 범위는 고통 4 이상으로 제한하는 변경으로 이해했습니다.",
      "highlights": {
        "primaryRole": "ENEMY",
        "attributes": [
          "HP",
          "ATK"
        ],
        "direction": "INCREASE",
        "scope": "고통 4 이상"
      },
      "warnings": [
        {
          "code": "UNKNOWN_ENTITY",
          "message": "변경 대상을 확인하고 필요하면 슬롯을 수정해주세요.",
          "entityName": "Wraith"
        }
      ]
    }
  }
}
```

**Processing Rules / Notes — Rules**

- `planId`는 최초 구조화 정보를 저장한 기획안 ID이며 양수인 `long`이다. 저장이 완료된 뒤 성공 응답을 반환한다.
- `genreIds`는 요청으로 받는 값이 아니라 현재 `gameId`의 장르를 서버가 조회해 반환한다.
- 사용자는 프론트에서 `slots`를 로컬 수정할 수 있다.
- 수정된 슬롯은 다음 `case-searches`의 `confirmedSlots`로 보낸다.
- `text`는 공백만인 값을 제외한 5~6000자다. 현재 AI 서버의 입력 제한과 같다.
- `changeType`은 `ADD`, `REMOVE`, `MODIFY`, `FIX`, `DEPRECATE` 중 하나다. AI의 `change_type`을 대문자로 변환해 전달한다.
- `direction`은 `INCREASE`, `DECREASE`, `NONE`, `NOT_APPLICABLE`, `UNKNOWN` 중 하나다.
- `targetRole`/`target.role`은 `PLAYER`, `ENEMY`, `WEAPON`, `ITEM`, `SKILL`, `MAP`, `SYSTEM`, `OTHER`, `UNKNOWN`을 사용한다.
- `attribute`는 AI가 추출한 원문 속성을 보존한다. `HP`, `ATK`만으로 제한하거나 임의 번역하지 않는다.
- 대상 이름/속성을 추출하지 못한 경우 빈 문자열, 수치/조건이 없으면 `magnitude`/`scope`는 `null`이다.
- `entities`는 대상 이름·역할별로 중복 제거하며 `source = AI`, `editable = false`다. AI 내부 규칙 보완 여부는 현재 내부 API가 구분하지 않는다.
- `slots`는 변경점별로 `editable = true`다. 순서대로 ID를 부여하며 조건 여러 개는 쉼표로 연결한다.
- `restatement.text`는 AI 재진술을 줄바꿈으로 연결한다. highlights의 역할/방향이 여러 종류면 `MIXED`, 변경점이 없으면 `UNKNOWN`이다.
- 대상 미확인은 `UNKNOWN_ENTITY`, 추출 결과가 비면 `NO_CHANGES` 경고와 빈 배열을 반환한다.


**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | 본문 `text`의 공백·길이 검증 실패 (필드별 `errors` 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | 잘못된 JSON·필수 본문 누락·경로 타입 오류 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `404` | `GAME_NOT_FOUND` | 게임을 찾을 수 없습니다. | 대상 게임 부재 |
| `503` | `AI_UNAVAILABLE` | AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요. | AI 장애. 최초 기획안 미저장 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 저장 실패 및 예상하지 못한 오류. 해당 저장 트랜잭션 전체 롤백 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다.

## 유사 사례 검색

### `POST /games/{gameId}/case-searches`

[유사 사례 검색 Notion 명세](https://app.notion.com/p/207776ebfd688395996e015a83be47e4)

**Auth**

- Required

**Request Body**

```json
{
  "planId": 101,
  "confirmedSlots": [
    {
      "target": {
        "name": "Axebot",
        "role": "ENEMY"
      },
      "attribute": "HP",
      "changeType": "MODIFY",
      "direction": "INCREASE",
      "scope": "고통 4 이상",
      "magnitude": "+20%"
    }
  ],
  "genreIds": [
    1
  ],
  "sort": "REVIEW_COUNT_DESC"
}
```

**Processing Rules / Notes — 현재 sort 예시**

- `SIMILARITY_DESC`
- `REVIEW_COUNT_DESC`
- `ABS_DELTA_PP_DESC`
- `PATCHED_ON_DESC`

기본값은 `SIMILARITY_DESC` 방향.

**검색 처리 기준**

- `planId`는 필수이며 양수인 `long`이다. 구조화 응답 또는 본인의 기존 기획안 내역에서 받은 ID를 전달한다. 회원 ID·원문·최초 엔티티·최초 슬롯·해석·경고는 요청으로 받지 않고 서버에 저장된 값을 사용한다.
- `confirmedSlots`는 1~20개다. 각 슬롯의 `target`, `attribute`, `changeType`, `direction`을 전달한다. 수치 비교를 위해 `magnitude`도 보존하며, 없으면 `null`이다. `scope`는 선택값이다.
- `genreIds`는 필수 배열이다. 빈 배열은 전체 장르, 값이 있으면 하나 이상의 장르가 일치하는 게임을 검색한다. 존재하지 않는 장르 ID는 `400 INVALID_REQUEST`다.
- 기존 `/embed/query`에 확인한 슬롯을 문장으로 변환해 전달한다. 같은 임베딩 모델·512차원의 성공 청크만 검색한다.
- 슬롯별 코사인 유사도 상위 30개를 구한 뒤 변경 종류와 방향을 필터링한다. 두 조건은 같은 `patch_change` 행에서 충족해야 한다. 추가·삭제·버그 수정·지원 중단은 방향 필터를 적용하지 않는다.
- 운영 검색은 `patch_chunk.embedding`의 HNSW 코사인 인덱스를 사용하는 근사 최근접 검색이다. 패치·통계·장르 조건을 충족하는 후보를 최대 30개 찾은 뒤 변경 종류·방향을 검사하며, 필터 순서는 유지한다. 근사 검색이므로 전체 벡터를 비교하는 정확 검색과 후보 집합이 일부 다를 수 있다.
- pgvector 0.8 이상에서 검색 트랜잭션에만 `hnsw.iterative_scan = strict_order`와 설정된 `hnsw.ef_search`를 적용한다. `app.patch-search.ef-search`는 환경변수 `PATCH_SEARCH_EF_SEARCH`로 지정하며, 미설정 시 기존 값 `100`을 사용한다. 허용값은 pgvector와 같은 `1~1000`의 정수이며 잘못된 설정은 애플리케이션 기동 시 실패한다. 설정은 시작 시 읽고 검색 트랜잭션 종료 후 다른 트랜잭션에 남기지 않는다. 필터로 후보가 줄면 탐색을 이어 가되 pgvector의 탐색 한도에 도달하면 30개 미만일 수 있다. 운영 DB에는 유효한 `idx_patch_chunk_embedding_hnsw` 인덱스가 필요하다. 거리순 인덱스 탐색을 유지하기 위해 패치·통계 조건은 상관 서브쿼리로 검사하고, 동률 정렬은 후보 선정 후 적용한다.
- `validation_status`가 `valid` 또는 `needs_review`인 변경점을 사용하며 `rejected`는 제외한다. 미확인 대상도 변경 종류·방향으로 검색 가능하다.
- 패치 확정(`news.is_patch = true`)이고 전후 리뷰 수가 모두 1 이상이며 긍정률이 있는 사례만 후보로 삼는다. 현재 게임의 과거 사례도 검색 가능하다.
- 여러 슬롯·청크에서 같은 패치가 검색되면 최고 유사도로 한 번만 반환한다. `totalCount`는 이 중복 제거 후 패치 수다.
- 표시 유사도는 코사인 유사도 × 100을 0~100 범위로 제한하고 소수점 한 자리로 반올림한다. 정렬은 반올림 전 유사도를 사용한다. 개발용 검색 스크립트의 후보 집합 내 최소·최대 정규화는 사용하지 않는다.
- 정렬은 결과군 내부에 적용한다. 동률이면 유사도 내림차순, 공지 ID 오름차순으로 순서를 고정한다.
- `reviewCount`는 패치 후 7일 집계인 `patch_stat.after_review_count`다. 전후 긍정률은 `patch_stat` 값을 사용하고, `deltaPp`는 두 값의 차이로 계산한다.
- 평균 주기는 해당 패치까지 관측된 게시 시각 간격의 산술평균이다. 이후 간격은 평균에 넣지 않는다. 간격은 초 차이를 86400으로 나눈 일수이며 이력이 부족하면 `null`이다.
- 다음 패치 간격은 같은 게임의 다음 패치 공지 게시 시각으로 계산한다. 다음 패치가 없거나 평균이 없거나 0이면 후속 배수는 `null`이다.
- 카드 요약과 비교는 기존 `/cases/cards`, `/cases/compare`를 사용한다. 검색 응답에서 모든 비교를 반환하므로 `use_llm = false`로 문장 틀 비교를 요청한다.
- 카드 요청은 결과군별 최대 60건으로 나누고 결과군 간 배치들을 병렬 실행한다. 이후 사례별 비교도 병렬 실행한다. 백엔드 인스턴스의 공유 실행기로 카드·비교의 동시 호출을 최대 4개로 제한하며, 완료 순서와 관계없이 기존 결과군·정렬 순서와 사례별 대응을 유지한다. 하나라도 실패하면 부분 성공을 반환하지 않고 남은 작업의 중단을 요청한다.
- 패치 AI 클라이언트는 성공한 health 확인을 5초간 재사용하며 동시 확인을 합친다. health 실패는 캐싱하지 않고 외부 POST 통신 실패 시 재사용 중인 성공 상태도 해제한다. 실제 POST와 응답 검증은 항상 수행한다.
- 검색마다 후보 수, 카드 배치 수, 전체 및 `validate`/`embed`/`search`/`cards`/`compare`/`assemble`/`save` 단계의 소요 시간을 서버 로그에 기록한다. 병렬 단계 시간은 합산 호출 시간이 아닌 대기열 대기를 포함한 경과 시간이며, health 확인 시간은 해당 AI 호출 단계에 포함된다. 로그에는 원문·근거 문장을 포함하지 않으며 API 응답 형식은 유지한다.
- 결과군 패턴은 AI의 기존 코드 빈도 규칙을 적용하며, 카드 호출을 나누더라도 전체 결과군을 기준으로 한 번 계산한다. 20건 미만이면 패턴은 빈 배열이며 `notices`에 소표본 안내를 반환한다.
- AI 비교 입력 제한에 따라 패치당 최대 200개 변경점을 사용하며 매칭된 청크를 우선한다. 잘린 사례가 있으면 `notices`에 알린다.
- 검색 결과가 없으면 `201`, `totalCount = 0`과 세 결과군의 빈 배열을 반환한다.


**Response 201**

```json
{
  "code": "201",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "status": "COMPLETED",
    "gameId": 1,
    "confirmedSlots": [
      {
        "target": {
          "name": "Axebot",
          "role": "ENEMY"
        },
        "attribute": "HP",
        "changeType": "MODIFY",
        "direction": "INCREASE",
        "scope": "고통 4 이상",
        "magnitude": "+20%"
      }
    ],
    "genreIds": [
      1
    ],
    "sort": "REVIEW_COUNT_DESC",
    "totalCount": 47,
    "groups": [
      {
        "outcome": "NEGATIVE_SHIFT",
        "name": "부정 급변",
        "caseCount": 1,
        "observedPatterns": [
          "스탯을 여러 개 동시에 조정",
          "전 난이도 적용",
          "메타 빌드에 직접 영향"
        ],
        "cases": [
          {
            "gameId": 5,
            "gameTitle": "Grim Ascension",
            "genres": [
              1
            ],
            "patchId": "1234567890",
            "patchTitle": "Patch 2.1.0 - Balance Update",
            "patchedOn": "2025-03-18",
            "similarity": 92.4,
            "reviewCount": 2418,
            "positiveRateBefore": 81.2,
            "positiveRateAfter": 54.6,
            "deltaPp": -26.6,
            "avgPatchIntervalDays": 12.4,
            "nextPatchIntervalDays": 5,
            "followUpSpeedRatio": 0.4,
            "commonalitySummary": "적 체력·공격력 상향이 핵심 축으로 일치합니다.",
            "differenceSummary": "사례는 전 난이도·보상 재화 하향이 동반되었습니다.",
            "comparison": {
              "commonalities": [
                {
                  "title": "변경 방향이 같습니다.",
                  "description": "내 기획안과 사례 모두 적 체력을 상향했습니다."
                },
                {
                  "title": "핵심 대상이 같습니다.",
                  "description": "두 변경 모두 적 유닛의 전투 난이도에 직접 영향을 줍니다."
                }
              ],
              "differences": [
                {
                  "title": "적용 범위가 다릅니다.",
                  "description": "내 기획안은 고통 4 이상에만 적용되지만 사례는 전 난이도에 적용됐습니다."
                },
                {
                  "title": "동시 변경 항목이 다릅니다.",
                  "description": "사례에는 보상 재화 하향이 함께 포함됐습니다."
                }
              ]
            }
          }
        ]
      },
      {
        "outcome": "NO_CHANGE",
        "name": "변화 없음",
        "caseCount": 0,
        "observedPatterns": [],
        "cases": []
      },
      {
        "outcome": "POSITIVE_SHIFT",
        "name": "긍정 급변",
        "caseCount": 0,
        "observedPatterns": [],
        "cases": []
      }
    ],
    "notices": [
      "유사도는 변경 슬롯 임베딩 유사도(0~100)이며 성공 확률이 아닙니다.",
      "후속 배수는 해당 패치~다음 패치 간격 / 평균 패치 주기입니다."
    ]
  },
  "success": true
}
```

**Processing Rules / Notes — Rules**

- 검색 결과 카드와 상세 비교 모두 이 응답을 사용한다.
- `commonalitySummary`, `differenceSummary`: 카드용 요약
- `comparison.commonalities[]`, `comparison.differences[]`: 사례 상세 화면용
- 상세 패치 본문은 별도 `GET /games/{gameId}/patches/{patchId}` 호출
- 다음 패치가 없으면 `nextPatchIntervalDays`, `followUpSpeedRatio`는 `null`
- `avgPatchIntervalDays`는 패치 공지 게시 시각 사이 간격의 산술평균을 사용한다. 중앙값을 사용하지 않는다.

**결과군 분류 기준 (2026-09-16 확정)**

`deltaPp`는 패치 후 긍정률에서 패치 전 긍정률을 뺀 퍼센트포인트(%p) 차이다. 상대 증감률(%)이 아니다.

| 조건 | `outcome` | `name` |
|---|---|---|
| `deltaPp <= -3` | `NEGATIVE_SHIFT` | 부정 급변 |
| `-3 < deltaPp < 3` | `NO_CHANGE` | 변화 없음 |
| `deltaPp >= 3` | `POSITIVE_SHIFT` | 긍정 급변 |

예: 긍정률 70% → 73%는 +3%p로 긍정 급변, 70% → 67%는 -3%p로 부정 급변이다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | `planId` 누락·양수 검증 또는 기존 요청 필드 검증 실패 (필드별 `errors` 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | 잘못된 JSON·필수 본문 누락·타입 오류·존재하지 않는 장르·본인 기획안의 대상 게임 불일치 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `404` | `GAME_NOT_FOUND` | 게임을 찾을 수 없습니다. | 대상 게임 부재 |
| `404` | `PATCH_PLAN_NOT_FOUND` | 기획안 내역을 찾을 수 없습니다. | 미존재 기획안 또는 다른 회원 소유의 기획안 |
| `503` | `AI_UNAVAILABLE` | AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요. | AI 장애. 이번 검색의 확정 정보 미저장 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 실패 및 예상하지 못한 오류. 이번 저장 변경 전체 롤백 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다. 미존재·타 회원 소유의 기획안은 게임 불일치 오류보다 먼저 판정해 타인의 기획안 정보를 노출하지 않는다.

## 기획안 내역 목록 조회

### `GET /members/me/patch-plans`

[기획안 내역 목록 조회 원본 명세](https://app.notion.com/p/3e2776ebfd68819bbe62e5223a843346)

현재 로그인 사용자가 저장한 기획안 내역을 최종 확정 상태 기준으로 최신순 조회한다.

**Auth**

- Required (`Authorization: Bearer {ACCESS_TOKEN}`)

**Path Variables / Request Body**: 없음

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `gameId` | long | No | 양수. 특정 게임의 기획안 내역만 조회하며 생략하면 모든 게임 조회 |
| `limit` | int | No | 기본 10, min 1, max 100 |
| `cursor` | string | No | 다음 페이지 조회용 커서. 첫 조회 시 생략 |

**Request Example**

```http
GET /members/me/patch-plans?gameId=730&limit=10
Authorization: Bearer {ACCESS_TOKEN}
```

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-21 14:40:00",
  "data": {
    "items": [
      {
        "planId": 101,
        "gameId": 730,
        "gameTitle": "Slay the Spire 2",
        "rawTextPreview": "Axebot의 체력을 20% 높이고 공격력을 10% 증가시킨다. 고통 4 이상 난이도에서만 적용하며, Wraith 계열 등장 빈도도 소폭 조정한다.",
        "slotCount": 3,
        "unknownEntityCount": 1,
        "createdAt": "2026-09-21T14:32:00+09:00"
      }
    ],
    "page": {
      "limit": 10,
      "nextCursor": null,
      "hasNext": false,
      "totalCount": 1
    }
  },
  "success": true
}
```

**Processing Rules / Notes — Field rules**

아래 필드 경로는 `data` 내부를 기준으로 한다.

| Field | Type | Description |
|---|---|---|
| `items` | object[] | 현재 회원과 `gameId` 필터에 해당하는 기획안 내역 목록 |
| `items[].planId` | long | 검색 실행별로 저장한 기획안 내역 ID (`patch_plan.patch_plan_id`) |
| `items[].gameId` | long | 기획안 대상 게임 ID (`patch_plan.appid`) |
| `items[].gameTitle` | string | 현재 게임 테이블의 게임 이름 (`game.name`) |
| `items[].rawTextPreview` | string | `patch_plan.raw_text` 앞부분 최대 200자. 별도 저장·AI 생성 없음 |
| `items[].slotCount` | int | 해당 검색에 실제 제출한 최종 확정 슬롯 개수 |
| `items[].unknownEntityCount` | int | 최종 확정 슬롯 중 `target_role = UNKNOWN`인 고유 `target_name` 수 |
| `items[].createdAt` | string | 검색 내역 저장 시각 (`patch_plan.created_at`). ISO 8601, 한국 시간대 (`+09:00`) |
| `page` | object | 페이지 정보 |
| `page.limit` | int | 적용된 페이지 크기 |
| `page.nextCursor` | string \| null | 다음 페이지 커서. 다음 페이지가 없으면 `null` |
| `page.hasNext` | boolean | 다음 페이지 존재 여부 |
| `page.totalCount` | long | 현재 회원과 `gameId` 필터에 해당하는 전체 내역 수. 커서 이전 내역도 포함 |

**Processing Rules / Notes — Rules**

- 인증된 회원의 기획안 내역만 조회하며 회원 ID를 별도로 입력받지 않는다.
- 저장된 내역을 `created_at DESC, patch_plan_id DESC` 순서로 정렬한다. 저장 시각이 같으면 내역 ID 내림차순으로 반환한다.
- 목록·`totalCount` 모두 `patch_plan.created_at IS NOT NULL` 조건을 적용한다. 기존 인덱스와 커서 형식은 유지하며 구조화 시각으로 정렬하지 않는다.
- 같은 원문으로 여러 번 검색한 경우 각각 별도의 내역으로 반환한다.
- `rawTextPreview`는 저장된 원문을 조회 시 앞부분 최대 200자로 잘라 반환한다. 별도 저장하거나 AI로 요약하지 않으며 제목 필드도 반환하지 않는다.
- `slotCount`는 해당 내역의 `patch_plan_confirmed_slot` 개수다. 최초 구조화 슬롯인 `patch_plan_slot` 개수를 사용하지 않는다.
- `unknownEntityCount`는 해당 내역의 최종 확정 슬롯 중 `target_role = UNKNOWN`인 행에서 `target_name` 기준으로 중복 제거한 대상 수다. 최초 구조화 엔티티나 당시 경고 개수를 사용하지 않는다.
- 커서는 현재 회원과 `gameId` 필터에 연결한다. 다른 회원 또는 다른 필터의 커서는 `400 INVALID_REQUEST`로 거부한다. `gameId` 생략(모든 게임)과 특정 게임 지정도 서로 다른 필터다.
- `totalCount`는 현재 회원과 `gameId` 필터에 해당하는 전체 내역 수이며, 커서 이전 내역도 포함한다. 한 응답의 목록과 전체 개수는 동일한 DB 스냅샷으로 조회한다.
- 빈 결과는 `200`, `items: []`, `hasNext: false`, `nextCursor: null`을 반환한다. 필터에 해당하는 전체 내역이 없으면 `totalCount`는 `0`이며, 커서 이후 결과만 비어 있으면 커서 이전 내역을 포함한 전체 개수를 유지한다.
- 게임 이름은 조회 시점의 `game.name`을 사용한다. 내 게임 등록을 해제하더라도 이미 저장한 기획안 내역은 조회할 수 있다.
- 저장된 검색 내역만 조회하며 AI 구조화나 유사 사례 검색을 실행하지 않는다. 기획안 상세 및 과거 유사 사례 검색 결과 조회는 이 API의 범위에 포함하지 않는다.
- 구조화만 완료했거나 검색에 실패한 미확정 기획안은 목록과 `totalCount`에서 제외한다. 검색 결과가 0건이어도 검색과 확정 저장이 성공한 내역은 포함한다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | `gameId` 양수·`limit` 범위 검증 실패 (필드별 `errors` 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | 파라미터 타입 오류·커서 형식 오류·회원 또는 필터가 다른 커서 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 조회 실패 및 예상하지 못한 서버 오류 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)에 따라 문자열 `code`, `message`, 한국 시간의
`responsedAt`을 포함하고 `data`, `success`는 포함하지 않는다. 필드 검증 오류가 있을 때만 `errors`를 포함한다.

입력값 검증 실패 예시:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해주세요.",
  "responsedAt": "2026-09-21 14:40:00",
  "errors": [
    {
      "field": "limit",
      "message": "limit은 1 이상 100 이하여야 합니다."
    }
  ]
}
```

## 기획안 내역 상세 조회

### `GET /members/me/patch-plans/{planId}`

[기획안 내역 상세 조회 원본 명세](https://app.notion.com/p/3e2776ebfd6881cdadece23076f0fe13)

현재 로그인 사용자가 저장한 기획안 원문, 당시 기획안 해석, 해당 검색에 실제 제출한 최종 확정 슬롯을 조회한다.

**Auth**

- Required (`Authorization: Bearer {ACCESS_TOKEN}`)

**Path Variables**

| Name | Type | Description |
|---|---|---|
| `planId` | long | 조회할 기획안 내역 ID. 1 이상 |

**Query Parameters**: 없음

**Request Body**: 없음

**Request Example**

```http
GET /members/me/patch-plans/101
Authorization: Bearer {ACCESS_TOKEN}
```

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-21 14:40:00",
  "data": {
    "planId": 101,
    "gameId": 730,
    "gameTitle": "Slay the Spire 2",
    "rawText": "Axebot의 체력을 20% 높이고 공격력을 10% 증가시킨다. 고통 4 이상 난이도에서만 적용하며, Wraith 계열 등장 빈도도 소폭 조정한다.",
    "restatement": {
      "text": "고통 4 이상 난이도에서 특정 적(Axebot)의 체력과 공격력을 함께 상향하는 변경입니다."
    },
    "genreIds": [9, 19],
    "confirmedSlots": [
      {
        "target": {
          "name": "Axebot",
          "role": "ENEMY"
        },
        "attribute": "체력",
        "changeType": "MODIFY",
        "direction": "INCREASE",
        "magnitude": "20%",
        "scope": "고통 4 이상"
      },
      {
        "target": {
          "name": "Axebot",
          "role": "ENEMY"
        },
        "attribute": "공격력",
        "changeType": "MODIFY",
        "direction": "INCREASE",
        "magnitude": "10%",
        "scope": "고통 4 이상"
      },
      {
        "target": {
          "name": "Wraith",
          "role": "UNKNOWN"
        },
        "attribute": "등장 빈도",
        "changeType": "MODIFY",
        "direction": "UNKNOWN",
        "magnitude": null,
        "scope": null
      }
    ],
    "createdAt": "2026-09-21T14:32:00+09:00"
  },
  "success": true
}
```

**Processing Rules / Notes — Field rules**

아래 필드 경로는 `data` 내부를 기준으로 한다.

| Field | Type | Description |
|---|---|---|
| `planId` | long | 저장한 기획안 내역 ID (`patch_plan.patch_plan_id`) |
| `gameId` | long | 기획안 대상 게임 ID (`patch_plan.appid`) |
| `gameTitle` | string | 현재 게임 테이블의 게임 이름 (`game.name`) |
| `rawText` | string | 사용자가 입력한 기획안 원문 전체 (`patch_plan.raw_text`) |
| `restatement` | object | 저장된 기획안 해석 |
| `restatement.text` | string | 당시 저장된 기획안 해석 문장 (`patch_plan_restatement.text`) |
| `genreIds` | int[] | 해당 검색에 사용한 장르 ID (`patch_plan_genre.genre_id`). 빈 배열은 전체 장르 조건 |
| `confirmedSlots` | object[] | 해당 검색에 실제 제출한 최종 확정 슬롯 |
| `confirmedSlots[].target` | object | 최종 확정 대상 |
| `confirmedSlots[].target.name` | string | 최종 대상 이름 (`patch_plan_confirmed_slot.target_name`) |
| `confirmedSlots[].target.role` | string (enum) | 최종 대상 역할 (`target_role`). `PLAYER`, `ENEMY`, `WEAPON`, `ITEM`, `SKILL`, `MAP`, `SYSTEM`, `OTHER`, `UNKNOWN` |
| `confirmedSlots[].attribute` | string | 최종 변경 속성 (`attribute`) |
| `confirmedSlots[].changeType` | string (enum) | 최종 변경 유형 (`change_type`). `ADD`, `REMOVE`, `MODIFY`, `FIX`, `DEPRECATE` |
| `confirmedSlots[].direction` | string (enum) | 최종 변경 방향 (`direction`). `INCREASE`, `DECREASE`, `NONE`, `NOT_APPLICABLE`, `UNKNOWN` |
| `confirmedSlots[].magnitude` | string \| null | 최종 변화량 문자열 (`magnitude`). 없으면 `null` |
| `confirmedSlots[].scope` | string \| null | 최종 적용 조건 및 범위 (`scope`). 없으면 `null` |
| `createdAt` | string | 검색 내역 저장 시각 (`patch_plan.created_at`). ISO 8601, 한국 시간대 (`+09:00`) |

**Processing Rules / Notes — Rules**

- 인증된 현재 회원 소유의 `planId`만 조회한다. 회원 ID를 별도로 입력받지 않는다.
- 검색과 확정 저장이 완료된 내역만 조회한다. `patch_plan.created_at IS NULL`인 미확정 기획안은 본인 소유여도 `404 PATCH_PLAN_NOT_FOUND`로 처리한다.
- 미존재 내역과 다른 회원 소유의 내역은 모두 동일한 `404 PATCH_PLAN_NOT_FOUND`로 처리하여 다른 회원의 내역 존재 여부를 노출하지 않는다.
- `rawText`는 저장된 원문 전체를 반환한다. 별도 제목, AI 요약, 축약본을 생성하거나 반환하지 않는다.
- `restatement.text`는 저장된 `patch_plan_restatement.text`를 그대로 반환한다. 조회 시 AI 재구조화·재진술을 실행하지 않는다.
- 본인 소유 내역에 `patch_plan_restatement` 행이 없으면 `200`과 `restatement: {"text": ""}`를 반환한다. 저장된 해석이 빈 문자열인 경우에도 그대로 반환한다.
- `confirmedSlots`는 `patch_plan_confirmed_slot`에서 조회하며 `slot_order` 오름차순으로 반환한다. 최초 AI 구조화의 `entities`, 최초 `slots`는 반환하지 않는다.
- `genreIds`는 검색 당시 선택값을 `patch_plan_genre`에서 조회한다. 현재 게임의 장르로 대체하지 않으며, `genreIds: []`는 기존 유사 사례 검색 계약과 동일하게 전체 장르 조건을 의미한다.
- 게임 이름은 조회 시점의 `game.name`을 사용한다.
- 화면의 미확인 표시는 `confirmedSlots[].target.role = UNKNOWN` 등 최종 슬롯 값으로 판단한다. 안내 문구가 필요하면 프론트가 최종 슬롯 상태를 기준으로 고정 문구를 표시한다. 안내를 위해 AI를 다시 호출하지 않는다.
- 상세 조회는 저장된 내역만 반환하며 유사 사례 검색을 실행하지 않는다. 과거 유사 사례 검색 결과도 응답에 포함하지 않는다.
- 기존 내역으로 다시 검색할 때는 `gameId`를 `POST /games/{gameId}/case-searches`의 Path Variable로, `planId`, `genreIds`, `confirmedSlots`를 Request Body로 전달한다. 재검색은 새로운 내역을 생성한다.
- 최종 확정 슬롯의 필드 구조·타입·코드 값은 기존 유사 사례 검색 API의 `confirmedSlots` 계약과 동일하다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | `planId` 양수 검증 실패 (필드별 `errors` 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | `planId` 타입 오류 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `404` | `PATCH_PLAN_NOT_FOUND` | 기획안 내역을 찾을 수 없습니다. | 미존재 내역·다른 회원 소유의 내역·검색 미확정 기획안 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 조회 실패 및 예상하지 못한 서버 오류 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)에 따라 문자열 `code`, `message`, 한국 시간의
`responsedAt`을 포함하고 `data`, `success`는 포함하지 않는다. 필드 검증 오류가 있을 때만 `errors`를 포함한다.

입력값 검증 실패 예시:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해주세요.",
  "responsedAt": "2026-09-21 14:40:00",
  "errors": [
    {
      "field": "planId",
      "message": "planId는 1 이상이어야 합니다."
    }
  ]
}
```

미존재 또는 다른 회원 소유의 내역 조회 실패 예시:

```json
{
  "code": "PATCH_PLAN_NOT_FOUND",
  "message": "기획안 내역을 찾을 수 없습니다.",
  "responsedAt": "2026-09-21 14:40:00"
}
```

## 패치 상세

### `GET /games/{gameId}/patches/{patchId}`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |
| `patchId` | string (`news.gid`) |

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:10:00",
  "data": {
    "patchId": "1234567890",
    "gameId": 1234560,
    "title": "Grim Ascension v2.1.0",
    "patchedOn": "2025-03-18",
    "publishedAt": "2025-03-18T09:00:00Z",
    "body": "Balance\n- Warden health +35%\n...",
    "bodyFormat": "PLAIN_TEXT",
    "url": "https://store.steampowered.com/news/app/1234560/view/999"
  },
  "success": true
}
```

**Processing Rules / Notes — DB mapping**

```text
patchId      <- news.gid
gameId       <- news.appid
title        <- news.title
publishedAt  <- news.published_ts
body         <- news.contents
url          <- news.url
patchedOn    <- news.published_ts의 KST 날짜 (공지 게시일)
```

**Error Responses**

- `401`
- `404 GAME_NOT_FOUND`: 게임을 찾을 수 없습니다.
- `404 PATCH_NOT_FOUND`: 패치를 찾을 수 없습니다. (해당 게임의 `is_patch = true` 공지만 조회)
- `500`

패치 본문은 [Steam 본문 일반 텍스트 변환](conventions.md#steam-본문-일반-텍스트-변환)으로 BBCode·HTML 서식을 변환합니다.
문단·목록·표·미디어 설명·알 수 없는 대괄호 표현과 코드 예시는 보존하며, DB 원문과 제목·`url` 필드는 변경하지 않습니다.
`publishedAt`은 UTC 시각, `patchedOn`은 같은 시각의 한국 날짜입니다.

## 패치노트 번역

### `GET /patches/{patchId}/translation`

[패치노트 번역 원본 명세](https://app.notion.com/p/3df776ebfd688156a926cbd844f44389)

**Auth**

- Required (`Authorization: Bearer {ACCESS_TOKEN}`)

**Path Variables**

| Name | Type | Description |
|---|---|---|
| `patchId` | string | 패치 ID (`news.gid`) |

**Query Parameters**: 없음

**Request Body**: 없음

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-18 13:20:00",
  "data": {
    "patchId": "1234567890",
    "translatedTitle": "밸런스 업데이트",
    "translatedBody": "무기 공격력이 20% 감소하고 적의 체력이 조정되었습니다."
  },
  "success": true
}
```

`data.patchId`, `data.translatedTitle`, `data.translatedBody`는 모두 `string`이다.

**Processing Rules / Notes — Rules**

- `patchId`는 `news.gid` 문자열을 그대로 사용하며, `is_patch = true`인 공지의 제목과 본문을 조회한다. 일반 공지 또는 없는 ID는 `404 PATCH_NOT_FOUND`다.
- 번역 대상 언어는 한국어로 고정하며, 외부 번역 API는 DeepL을 사용한다.
- 클라이언트에서 번역할 패치노트 원문을 직접 전달하지 않는다.
- 번역한 제목은 `translatedTitle`, 번역한 본문은 `translatedBody`로 반환한다.
- 본문은 패치 상세와 동일한 [Steam 본문 일반 텍스트 변환](conventions.md#steam-본문-일반-텍스트-변환)을 적용한 후 번역한다. 제목은 저장된 문자열을 사용한다.
- 제목 또는 변환된 본문이 빈 문자열·공백뿐이면 해당 값은 DeepL을 호출하지 않고 그대로 반환한다. 본문 변환 과정에서 앞뒤 공백과 서식만 있는 본문은 빈 문자열이 될 수 있다.
- `news`에는 원문 언어 컬럼이 없으므로 한국어를 포함한 비어 있지 않은 값은 DeepL의 언어 자동 감지로 한국어(`KO`) 번역을 요청한다. 별도 언어 추정으로 호출을 생략하지 않는다.
- 제목과 본문은 각각 공통 DeepL 클라이언트로 번역한다. 각 JSON 요청 본문의 UTF-8 크기가 128 KiB를 초과하면 해당 외부 호출 없이 `502 TRANSLATION_UNAVAILABLE`로 처리한다. 원문을 자르거나 분할하지 않고 자동 재시도하지 않는다.
- DeepL 키 미설정·인증 오류, 미지원 언어, 입력 한도 초과, 할당량 초과, 연결 실패·시간 초과 및 잘못된 응답은 `502 TRANSLATION_UNAVAILABLE`로 처리한다.
- 제목 또는 본문 중 하나라도 번역에 실패하면 전체 요청을 `502`로 응답한다. 부분 번역이나 원문을 성공 응답으로 대신 반환하지 않는다.
- 번역 결과를 캐시하거나 DB에 저장하지 않는다. 각 요청에서 조회한 원문을 사용하며 외부 호출 중 DB 트랜잭션을 유지하지 않는다.

**Error Responses**

- `401 UNAUTHORIZED`: 인증이 필요합니다.
- `404 PATCH_NOT_FOUND`: 패치를 찾을 수 없습니다.
- `502 TRANSLATION_UNAVAILABLE`: 번역 서비스를 이용할 수 없습니다.
- `500 INTERNAL_SERVER_ERROR`: 서버 내부 오류가 발생했습니다.

오류 응답은 [공통 오류 계약](conventions.md#error-response)에 따라 `code`, `message`, `responsedAt`을
사용하며, 원본 명세의 `success: false`는 포함하지 않는다. DB 등 일반 서버 오류는 DeepL 오류로 변환하지 않는다.

위 처리 정책은 S15P21A202-260 구현 시 사용자 확인으로 확정했다.
DeepL 입력 크기·언어 감지 기준: [공식 번역 API 문서](https://developers.deepl.com/api-reference/translate/request-translation).

## AI 장애 응답 (2026-09-16 사용자 결정)

기획안 구조화·유사 사례 검색처럼 AI가 필요한 작업에서 서버 미준비·연결 실패·시간 초과·사용할 수 없는
AI 응답은 HTTP `503` / `AI_UNAVAILABLE`로 구분한다. 오류 메시지는
`AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.`다.
공통 오류 형식인 `code`, `message`, `responsedAt`을 사용하며 `data`, `success`는 넣지 않는다.
프론트는 입력과 기존 화면을 유지하면서 해당 작업 영역에 안내한다. 빈 검색 결과나 성공으로 표시하지 않는다.
DB 및 일반 백엔드 오류는 기존 HTTP 500을 유지한다. 패치 원문 조회는 AI 장애와 무관하게 동작한다.
구조화·검색 화면 API는 HTTP client와 연결되어 있다. 실제 AI 서버를 포함한 검증 방법은 [AI 연결 문서](../ai-patch-connection.md)를 따른다.
