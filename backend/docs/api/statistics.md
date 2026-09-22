# statistics API

패키지: `com.ssafy.thispatch.domain.statistics` · [공통 규칙](conventions.md)

## 조회 API 공통

- 플레이타임 표본 부족 응답의 `fallback.itemsByBand[].items[].body`와 언어별 상세의
  `representativeReviews[].body`에도 [Steam 본문 일반 텍스트 변환](conventions.md#steam-본문-일반-텍스트-변환)을 적용한다.
  저장 원문·AI 요약 입력·표본 건수·대표 리뷰 선정 기준은 유지한다.
- AI 호출 설정과 검증 범위는 [AI 연결](../ai-connection.md)을 따른다.
- 반응 추세·플레이타임·언어별 AI 요약의 검증된 생성 결과는 Redis에 기본 30분 저장한다.
  조회 기간과 실제 AI 입력이 같을 때만 재사용하며, 입력이 변경되면 새로 생성한다.
  DB 조회·표본 검사와 기간·대상 건수·대표 리뷰 구성은 매번 수행한다. API 응답 형식은 유지한다.
  같은 입력의 동시 생성은 공유하며 실패와 문장 틀 대체 결과는 저장하지 않는다.
- 플레이타임·언어별 AI 요약은 백엔드가 고른 리뷰를 `POST /reviews/summarize`로 보낸다.
  선택 대상이 30건 미만이면 AI 호출 없이 `SKIPPED`를 반환한다.
  준비 미완료·연결 실패·시간 초과·응답 검증 실패는 HTTP 200 안에서 요약만 `UNAVAILABLE`로 처리한다.
  언어별 상세도 표본 부족 시 요약의 `text`, `usedReviewCount`, `selection`은 `null`이며 대표 리뷰는 반환한다.
  언어별 대표 리뷰는 도움됨 수·리뷰 ID 내림차순 최대 4건이다.

### AI 일시 이용 불가 (2026-09-16 사용자 결정)

AI 장애로 통계나 대표 리뷰를 숨기지 않는다. 플레이타임 요약·언어별 상세는 기존 응답을 유지하며
요약의 `status`는 `UNAVAILABLE`, `reasonCode`는 `AI_UNAVAILABLE`, `message`는
`AI 요약을 일시적으로 이용할 수 없습니다.`로 반환한다.
`text`, `usedReviewCount`, `selection`은 `null`이고 플레이타임의 `recurringExpressions`는 빈 배열이다.
기간과 `targetReviewCount`는 실제 조회 값을 유지한다. 언어별 `representativeReviews`도 유지한다.
프론트는 이 상태에서 요약 영역에 안내를 표시하고 기존 차트·통계·원문을 계속 표시한다.

표본 부족은 별개로 `SKIPPED` / `INSUFFICIENT_SAMPLE`이다. 언어 상세에도 해당 `reasonCode`를 반환한다.
정상 완료 시 새 `message` 필드는 생략한다. DB 조회나 백엔드 내부 오류는 계속 HTTP 500으로 반환하며
AI 장애로 숨기지 않는다. 반응 추세 요약은 별도 `POST /trends/summarize`로 통계를 전달한다.
이 API의 문장 틀 대체 결과(`used_llm: false, clean: false`)도 정상 요약으로 사용한다.

- 반응 추세·플레이타임 토픽·언어별 분석의 전체 성공 응답은 원본 노션 명세의
  `code`, `message`, `responsedAt`, `data`, `success` 형식이다. 아래 `data`만 있는 예시는 축약이다.
- 표본 충분 여부는 모든 위치에서 `isSufficientSample`로 통일한다. 최소 표본 수 30건을 포함하면 충분하다.
- 서비스 전에 적재가 완료된다는 전제로 `dataStatus`는 `AVAILABLE`이다.
- 반응 추세의 `lastCollectedAt`은 리뷰 수집 시각 연결 전까지 `null`이다. 데이터 버전 기능은 보류한다.
- 없는 게임은 `404 GAME_NOT_FOUND`, 잘못된 날짜·구간 번호는 `400 INVALID_REQUEST`다. 미래 시작일은 허용하지 않는다.
- 데이터 조회는 읽기 전용이며 테이블·데이터·마이그레이션을 생성하거나 변경하지 않는다.

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

서비스 제공 전에 수집·적재를 완료한다는 전제다. 조회 기간에서 `daily_stat` 행이 없는 날짜도
응답에 포함하고 모든 건수를 0으로 반환한다. 해당 날짜의 `dataAvailable`은 `true`다.
행이 없다는 이유로 미처리 상태를 추정하지 않는다.

`positiveRate`는 분모가 0이면 `null`.

`availablePeriod`는 전체 일별 통계의 첫 날짜부터 오늘까지다. 통계 이력이 전혀 없으면 `null`이다.
기존 행의 `negative_count`가 `null`이면 리뷰 수에서 첫 작성·수정 채널의 긍정 수를 빼서 계산한다.

**Processing Rules / Notes — Patch rules**

- `patches[].id` = `news.gid`
- `patches[].patchedOn` = 공지 게시 시각(`news.published_ts`)의 KST 날짜
- 패치 날짜는 공지 게시일로 통일하며, 적용일 추출이나 `patch_stat` 생성 여부에 의존하지 않는다.
- `patchIndex` = 해당 게임 전체 패치 이력에서 시간순 위치
- `totalPatchCount` = 해당 게임 전체 패치 수

**Error Responses**

- `400`: 시작 날짜 오류
- `401`: 인증 필요
- `404`: 게임 없음
- `500`: 서버 내부 오류

## 반응 추세 AI 요약

### `GET /games/{gameId}/summaries/reaction-trends`

**현재 구현 범위**

- 시작일·종료일 모두 KST 날짜이며 양 끝을 포함한다. 역전된 기간과 미래 종료일, 400일 초과 기간은 `400`이다.
- 요청한 기간의 일별 리뷰 수 합계가 30건 미만이면 `SKIPPED` / `INSUFFICIENT_SAMPLE`이다.
- 30건 이상이면 일별 통계와 선택 기간의 패치 공지를 `POST /trends/summarize`로 전달한다.
  `window_days: 7`, `use_llm: true`이며, 날짜는 공지 게시일의 KST 날짜다.
- 모델 요약 또는 문장 틀 대체 결과를 받으면 `COMPLETED`다.
  `summary.text`에는 AI의 `summary`와 `caveats`를 줄바꿈으로 함께 반환한다. 응답 필드는 유지한다.
- AI가 허용하는 패치 수는 최대 50개다. 선택 기간에 51개 이상이면 패치를 임의로 제외하지 않고
  요약만 `UNAVAILABLE` / `AI_UNAVAILABLE`로 반환한다. 반응 추세 통계 조회에는 이 제한을 적용하지 않는다.
- 접속 실패·시간 초과·사용 불가능한 응답은 `UNAVAILABLE` / `AI_UNAVAILABLE`이며, 안내 문구는
  `AI 요약을 일시적으로 이용할 수 없습니다.`다. `text`는 `null`, `targetPeriod`는 요청 기간을 유지한다.
- AI의 통계 입력 검증 오류(`400`/`422`)는 백엔드 내부 오류(`500`)로 처리한다.
- `lastCollectedAt`은 수집 시각 연결 전까지 `null`이다.

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

빈 구간이 있어도 B1~B4를 유지한다. 분위 경계가 같더라도 구간을 합치거나 번호를 다시 매기지 않는다.
빈 구간의 리뷰 수는 0이며, 경계값과 같은 플레이타임은 해당 경계에서 시작하는 구간에 포함한다.

`scale.source = "ALL_GAME_REVIEWS"`.

전체 기간에 계산된 `band_stat`의 네 경계를 사용하고, 기간별 건수와 토픽 언급 수는 최신 `recent_review`와
`review_topic`을 조회한다. 전체 기간 `band_topic_stat`의 수치를 기간별 수치로 대신하지 않는다.
플레이타임 결측·음수 리뷰는 구간 분석에서 제외한다. 경계가 아직 없는 경우 최근 14일로 경계를 새로 만들지 않는다.

표본 기준은 선택 구간의 리뷰 수(전체 선택이면 네 구간 합계)다. 30건 미만이면 기존 fallback 형식으로
해당 리뷰를 도움됨 수·리뷰 ID 내림차순으로 제공하고 `itemsByBand`는 빈 구간까지 B1~B4를 유지한다.
전체 기간 경계 데이터도 없으면 `scale.sampleCount`는 0, 세 분위 값은 `null`이다.
`topics`는 토픽 ID 순서이며, 언급률의 분모는 해당 구간 리뷰 수다. `differencePp`는 선택 구간 언급률에서
전체 언급률을 뺀 값이며, 전체 선택이면 `null`이다. `highestBand`는 비어 있지 않은 구간 중 언급률 최대 구간이고
동률이면 작은 구간 번호를 사용한다.

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
    "isSufficientSample": true,
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
      "isSufficientSample": true
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
        "isSufficientSample": true
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
    "isSufficientSample": false,
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
    "isSufficientSample": true,
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
        "isSufficientSample": true
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
