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
    "genreIds": [1, 2],
    "entities": [
      {
        "id": 1,
        "name": "Axebot",
        "role": "ENEMY",
        "source": "RULE",
        "editable": false
      }
    ],
    "slots": [
      {
        "id": 1,
        "targetName": "Axebot",
        "targetRole": "ENEMY",
        "attribute": "HP",
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
        "attributes": ["HP", "ATK"],
        "direction": "INCREASE",
        "scope": "고통 4 이상"
      },
      "warnings": [
        {
          "code": "UNKNOWN_ENTITY",
          "message": "Wraith는 UNKNOWN이라 검색 가중치가 낮습니다.",
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
      "direction": "INCREASE",
      "scope": "고통 4 이상"
    }
  ],
  "genreIds": [1],
  "sort": "REVIEW_COUNT_DESC"
}
```

**Processing Rules / Notes — 현재 sort 예시**

- `SIMILARITY_DESC`
- `REVIEW_COUNT_DESC`
- `ABS_DELTA_PP_DESC`
- `PATCHED_ON_DESC`

기본값은 `SIMILARITY_DESC` 방향.

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
        "direction": "INCREASE",
        "scope": "고통 4 이상"
      }
    ],
    "genreIds": [1],
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
            "genres": [1],
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
publishedAt  <- news.published_at
body         <- news.contents
url          <- news.url
patchedOn    <- patch_stat.patched_at
```

**Error Responses**

- `401`
- `404`: 패치 없음
- `500`
