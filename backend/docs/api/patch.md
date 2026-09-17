# patch API

패키지: `com.ssafy.thispatch.domain.patch` · [공통 규칙](conventions.md)

## 변경점 구조화

### `POST /games/{gameId}/plan-structures`

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

- `400`: 기획안 본문 없음
- `401`
- `404`
- `500`

## 유사 사례 검색

### `POST /games/{gameId}/case-searches`

**Auth**

- Required

**Request Body**

```json
{
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

- `confirmedSlots`는 1~20개다. 각 슬롯의 `target`, `attribute`, `changeType`, `direction`을 전달한다. 수치 비교를 위해 `magnitude`도 보존하며, 없으면 `null`이다. `scope`는 선택값이다.
- `genreIds`는 필수 배열이다. 빈 배열은 전체 장르, 값이 있으면 하나 이상의 장르가 일치하는 게임을 검색한다. 존재하지 않는 장르 ID는 `400 INVALID_REQUEST`다.
- 기존 `/embed/query`에 확인한 슬롯을 문장으로 변환해 전달한다. 같은 임베딩 모델·512차원의 성공 청크만 검색한다.
- 슬롯별 코사인 유사도 상위 30개를 구한 뒤 변경 종류와 방향을 필터링한다. 두 조건은 같은 `patch_change` 행에서 충족해야 한다. 추가·삭제·버그 수정·지원 중단은 방향 필터를 적용하지 않는다.
- `validation_status`가 `valid` 또는 `needs_review`인 변경점을 사용하며 `rejected`는 제외한다. 미확인 대상도 변경 종류·방향으로 검색 가능하다.
- 패치 확정(`news.is_patch = true`)이고 전후 리뷰 수가 모두 1 이상이며 긍정률이 있는 사례만 후보로 삼는다. 현재 게임의 과거 사례도 검색 가능하다.
- 여러 슬롯·청크에서 같은 패치가 검색되면 최고 유사도로 한 번만 반환한다. `totalCount`는 이 중복 제거 후 패치 수다.
- 표시 유사도는 코사인 유사도 × 100을 0~100 범위로 제한하고 소수점 한 자리로 반올림한다. 정렬은 반올림 전 유사도를 사용한다. 개발용 검색 스크립트의 후보 집합 내 최소·최대 정규화는 사용하지 않는다.
- 정렬은 결과군 내부에 적용한다. 동률이면 유사도 내림차순, 공지 ID 오름차순으로 순서를 고정한다.
- `reviewCount`는 패치 후 7일 집계인 `patch_stat.after_review_count`다. 전후 긍정률은 `patch_stat` 값을 사용하고, `deltaPp`는 두 값의 차이로 계산한다.
- 평균 주기는 해당 패치까지 관측된 게시 시각 간격의 산술평균이다. 이후 간격은 평균에 넣지 않는다. 간격은 초 차이를 86400으로 나눈 일수이며 이력이 부족하면 `null`이다.
- 다음 패치 간격은 같은 게임의 다음 패치 공지 게시 시각으로 계산한다. 다음 패치가 없거나 평균이 없거나 0이면 후속 배수는 `null`이다.
- 카드 요약과 비교는 기존 `/cases/cards`, `/cases/compare`를 사용한다. 검색 응답에서 모든 비교를 반환하므로 `use_llm = false`로 문장 틀 비교를 요청한다.
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

- `400`
- `401`
- `404`
- `500`

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

패치 원문의 Steam BBCode 서식은 일반 텍스트로 변환합니다. 문단·목록·알 수 없는 대괄호 표현과 코드 예시는 보존합니다. `publishedAt`은 UTC 시각, `patchedOn`은 같은 시각의 한국 날짜입니다.

## AI 장애 응답 (2026-09-16 사용자 결정)

기획안 구조화·유사 사례 검색처럼 AI가 필요한 작업에서 서버 미준비·연결 실패·시간 초과·사용할 수 없는
AI 응답은 HTTP `503` / `AI_UNAVAILABLE`로 구분한다. 오류 메시지는
`AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.`다.
공통 오류 형식인 `code`, `message`, `responsedAt`을 사용하며 `data`, `success`는 넣지 않는다.
프론트는 입력과 기존 화면을 유지하면서 해당 작업 영역에 안내한다. 빈 검색 결과나 성공으로 표시하지 않는다.
DB 및 일반 백엔드 오류는 기존 HTTP 500을 유지한다. 패치 원문 조회는 AI 장애와 무관하게 동작한다.
구조화·검색 화면 API는 HTTP client와 연결되어 있다. 실제 AI 서버를 포함한 검증 방법은 [AI 연결 문서](../ai-patch-connection.md)를 따른다.
