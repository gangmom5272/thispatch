# statistics API

패키지: `com.ssafy.thispatch.domain.statistics` · [공통 규칙](conventions.md)

## 일별 분석 / 기간 합계 / 채널 분해

### `GET /games/{gameId}/reaction-trends`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `startDate` | date | Yes | 포함 시작일, Asia/Seoul |

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 13:30:00",
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-07-30",
        "endDate": "2026-09-09",
        "dayCount": 42
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "lastCollectedAt": "2026-09-09T04:00:00Z",
      "dataStatus": "AVAILABLE"
    },
    "availablePeriod": {
      "startDate": "2026-01-01",
      "endDate": "2026-09-09",
      "dayCount": 252
    },
    "summary": {
      "reviewCount": 5320,
      "positiveCount": 3810,
      "negativeCount": 1510,
      "positiveRate": 71.6,
      "firstWrittenCount": 3100,
      "firstWrittenPositiveCount": 2350,
      "firstWrittenNegativeCount": 750,
      "firstWrittenPositiveRate": 75.8,
      "updatedCount": 2220,
      "updatedPositiveCount": 1460,
      "updatedNegativeCount": 760,
      "updatedPositiveRate": 65.8
    },
    "daily": [
      {
        "date": "2026-08-27",
        "dataAvailable": true,
        "reviewCount": 127,
        "positiveCount": 87,
        "negativeCount": 40,
        "positiveRate": 68.5,
        "firstWrittenCount": 73,
        "updatedCount": 54,
        "firstWrittenPositiveCount": 54,
        "firstWrittenNegativeCount": 19,
        "updatedPositiveCount": 33,
        "updatedNegativeCount": 21,
        "patches": [
          {
            "id": "1234567890",
            "title": "v1.4.0 Balance Adjustment",
            "patchedOn": "2026-08-27",
            "patchIndex": 6,
            "totalPatchCount": 7
          }
        ]
      }
    ]
  },
  "success": true
}
```

**Processing Rules / Notes — 집계 규칙**

`daily_stat` 기준:

```text
reviewCount               = daily_stat.review_count
negativeCount             = daily_stat.negative_count
positiveCount             = reviewCount - negativeCount

firstWrittenCount         = new_review_count
firstWrittenPositiveCount = new_positive_count
firstWrittenNegativeCount = new_review_count - new_positive_count

updatedCount              = edited_review_count
updatedPositiveCount      = edited_positive_count
updatedNegativeCount      = edited_review_count - edited_positive_count
```

기간 `summary`는 선택 기간 `daily_stat` 합계로 계산한다.

`positiveRate`는 분모가 0이면 `null`.

**Processing Rules / Notes — Patch rules**

- `patches[].id` = `news.gid`
- `patchIndex` = 해당 게임 전체 패치 이력에서 시간순 위치
- `totalPatchCount` = 해당 게임 전체 패치 수

**Error Responses**

- `400`: 시작 날짜 오류
- `401`: 인증 필요
- `404`: 게임 없음
- `500`: 서버 내부 오류

## 반응 추세 AI 요약

### `GET /games/{gameId}/summaries/reaction-trends`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Query Parameters**

| Name | Type | Required |
|---|---|---:|
| `startDate` | date | Yes |
| `endDate` | date | Yes |

**Response 200 - COMPLETED**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 13:31:00",
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "lastCollectedAt": "2026-09-09T04:00:00Z",
      "dataStatus": "AVAILABLE"
    },
    "summary": {
      "status": "COMPLETED",
      "text": "수정 채널 비중이 평소 대비 약 2.1배 늘었고, 수정 채널 긍정률은 첫 작성보다 13.5%p 낮습니다. 해당 구간에 밸런스 패치가 포함되어 있습니다.",
      "targetPeriod": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "reasonCode": null
    }
  },
  "success": true
}
```

**Response 200 - SKIPPED**

표본 부족 시:

```json
{
  "summary": {
    "status": "SKIPPED",
    "text": null,
    "reasonCode": "INSUFFICIENT_SAMPLE"
  }
}
```

**Error Responses**

- `400`
- `401`
- `404`
- `500`

## 구간별 토픽 분석

### `GET /games/{gameId}/playtime-topics`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `startDate` | date | No | 없으면 오늘 -13일 |
| `bandNo` | int | No | 생략 시 전체, 허용값 1~4 |

**Processing Rules / Notes — 핵심 규칙**

플레이타임 band는 최근 14일 데이터로 새로 계산하지 않는다.

1. 게임 전체 리뷰의 `playtime_at_review`로 Q1/Q2/Q3 계산
2. 고정 band 경계 생성
3. 조회 기간 리뷰를 해당 경계에 분류
4. 최근 리뷰 통계/토픽 집계

```text
B1 = [0, Q1)
B2 = [Q1, Q2)
B3 = [Q2, Q3)
B4 = [Q3, ∞)
```

`scale.source = "ALL_GAME_REVIEWS"`.

**Response 주요 구조**

```json
{
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "dataStatus": "AVAILABLE"
    },
    "selectedBand": "ALL",
    "minimumSampleCount": 30,
    "sampleSufficient": true,
    "scale": {
      "source": "ALL_GAME_REVIEWS",
      "sampleCount": 84210,
      "p25Minutes": 90,
      "medianMinutes": 480,
      "p75Minutes": 2100
    },
    "overall": {
      "band": "ALL",
      "minMinutes": 0,
      "maxMinutesExclusive": null,
      "reviewCount": 486,
      "positiveCount": 332,
      "negativeCount": 154,
      "positiveRate": 68.4,
      "sampleSufficient": true
    },
    "bands": [
      {
        "band": "B1",
        "minMinutes": 0,
        "maxMinutesExclusive": 90,
        "reviewCount": 120,
        "positiveCount": 86,
        "negativeCount": 34,
        "positiveRate": 71.7,
        "sampleSufficient": true
      }
    ],
    "topics": [
      {
        "topicId": 1,
        "name": "밸런스/너프·버프",
        "mentionCount": 212,
        "mentionRate": 43.6,
        "overallMentionRate": 43.6,
        "differencePp": null,
        "highestBand": {
          "band": "B4",
          "mentionRate": 68.2
        }
      }
    ],
    "fallback": null
  }
}
```

**Processing Rules / Notes — 표본 부족 fallback**

```json
{
  "data": {
    "sampleSufficient": false,
    "bands": [],
    "topics": [],
    "fallback": {
      "reasonCode": "INSUFFICIENT_SAMPLE",
      "message": "표본이 부족해 구간·토픽 집계 대신 원문을 표시합니다.",
      "totalCount": 22,
      "itemsByBand": [
        {
          "band": "B1",
          "items": [
            {
              "id": 12345,
              "sentiment": "NEGATIVE",
              "reviewDate": "2026-09-01",
              "playtimeMinutes": 40,
              "languageCode": "english",
              "body": "..."
            }
          ]
        }
      ]
    }
  }
}
```

**Error Responses**

- `400`: 유효하지 않은 `bandNo`
- `401`
- `404`
- `500`

## 대표 반응 AI 요약

### `GET /games/{gameId}/summaries/playtime-topics`

**Auth**

- Required

**Query Parameters**

| Name | Type | Required |
|---|---|---:|
| `startDate` | date | No |
| `bandNo` | int | No |

**Response 200 - COMPLETED**

```json
{
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "dataStatus": "AVAILABLE"
    },
    "selectedBand": "ALL",
    "summary": {
      "status": "COMPLETED",
      "text": "플레이타임이 길수록 밸런스 언급이 늘고, 초반 구간에서는 튜토리얼·프레임 드랍 언급이 상대적으로 많습니다.",
      "recurringExpressions": [
        "밸런스 조정",
        "보상 재화",
        "크래시",
        "빌드 다양성",
        "프레임 드랍",
        "튜토리얼"
      ],
      "targetPeriod": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "targetReviewCount": 486,
      "usedReviewCount": 40,
      "selection": {
        "code": "HELPFUL_DESC_PER_PLAYTIME_BUCKET",
        "limit": 40,
        "description": "선택 구간(전체) 기준 도움됨 상위 리뷰를 사용해 요약했습니다."
      },
      "reasonCode": null
    }
  }
}
```

**Response 200 - SKIPPED**

```json
{
  "summary": {
    "status": "SKIPPED",
    "text": null,
    "recurringExpressions": [],
    "targetReviewCount": 22,
    "usedReviewCount": null,
    "selection": null,
    "reasonCode": "INSUFFICIENT_SAMPLE"
  }
}
```

**Error Responses**

- `400`: 유효하지 않은 `bandNo`
- `401`
- `404`
- `500`

## 언어별 분석 조회

### `GET /games/{gameId}/language-analysis`

**Auth**

- Required

**Response 200**

```json
{
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "dataStatus": "AVAILABLE"
    },
    "totalReviewCount": 120,
    "sampleSufficient": true,
    "minimumSampleCount": 30,
    "languages": [
      {
        "languageCode": "english",
        "displayName": "영어",
        "reviewCount": 80,
        "reviewShare": 66.7,
        "positiveRate": 70,
        "positiveCount": 56,
        "negativeCount": 24,
        "excludedForInsufficientSample": true
      },
      {
        "languageCode": "korean",
        "displayName": "한국어",
        "reviewCount": 30,
        "reviewShare": 25,
        "positiveRate": 70,
        "positiveCount": 21,
        "negativeCount": 9,
        "isSufficientSample": true
      }
    ]
  }
}
```

**Error Responses**

- `401`
- `404`

## 언어별 요약 / 대표 리뷰 조회

### `GET /games/{gameId}/language-analysis/{languageCode}`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |
| `languageCode` | string |

**Response 200**

```json
{
  "data": {
    "meta": {
      "period": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "timezone": "Asia/Seoul",
      "aggregationBasis": "UPDATED_AT",
      "dataStatus": "AVAILABLE"
    },
    "languageCode": "english",
    "summary": {
      "status": "COMPLETED",
      "text": "전투가 길어졌다는 의견이 반복적으로 나타납니다.",
      "targetPeriod": {
        "startDate": "2026-08-27",
        "endDate": "2026-09-09",
        "dayCount": 14
      },
      "targetReviewCount": 80,
      "usedReviewCount": 20,
      "selection": {
        "code": "HELPFUL_DESC",
        "limit": 20,
        "description": "80건 중 도움됨 상위 20건"
      }
    },
    "representativeReviews": [
      {
        "id": 1,
        "sentiment": "NEGATIVE",
        "isUpdated": true,
        "playtimeMinutes": 7200,
        "languageCode": "english",
        "helpfulCount": 412,
        "tags": [
          {
            "id": 1,
            "name": "밸런스/너프·버프"
          }
        ],
        "body": "적 체력 증가 이후 전투가 너무 길어졌습니다.",
        "reviewDate": "2026-09-08"
      }
    ]
  }
}
```

**Error Responses**

- `400`: 언어 코드 오류
- `401`
- `404`
