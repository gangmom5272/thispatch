# review API

패키지: `com.ssafy.thispatch.domain.review` · [공통 규칙](conventions.md)

## 리뷰 목록 조회

### `GET /games/{gameId}/reviews`

**Auth**

- Required

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `topicIds` | int[] | No | 여러 개 선택 시 OR |
| `cursor` | string | No | 다음 페이지 커서 |
| `limit` | int | No | 기본 10, min 1, max 100 |

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
      "lastCollectedAt": "2026-09-09T08:00:00Z"
    },
    "items": [
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
    ],
    "page": {
      "limit": 20,
      "nextCursor": null,
      "hasNext": false,
      "totalCount": 1
    }
  }
}
```

`items[].id`는 `long`.

**Error Responses**

- `400`: 토픽 ID 또는 페이지 커서 오류
- `401`
- `404`

## 최근 대표 리뷰 조회

### `GET /games/{gameId}/reviews/representative`

**Auth**

- Required

**Query Parameters**: 없음

**Response item**

```json
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
```

**Processing Rules / Notes — 현재 처리 규칙**

- 최근 14일 리뷰 중 대표 리뷰 반환
- 현재 명세: 최대 3건
- 선정 기준은 서버 정책

**Error Responses**

- `401`
- `404`
