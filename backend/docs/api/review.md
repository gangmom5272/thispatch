# review API

패키지: `com.ssafy.thispatch.domain.review` · [공통 규칙](conventions.md)

## 리뷰 목록·대표 리뷰의 응답 형식과 조회 기준

- [리뷰 목록 원본 명세](https://splendid-snout-4a6.notion.site/d519f3f2785282da8db581071c91d8a4)와
  [대표 리뷰 원본 명세](https://splendid-snout-4a6.notion.site/4f19f3f2785282e3aab381a845eab9a1)의 전체 성공 응답은
  `code`, `message`, `responsedAt`, `data`, `success`다. 아래 목록의 JSON은 `data` 중심의 축약 예시다.
- 두 API 모두 KST 기준 오늘을 포함한 최근 14일의 최신 리뷰를 조회한다. 기간 필터 전에 리뷰의 최신 수정 버전을 선택한다.
- `lastCollectedAt`은 수집 시각 칼럼·적재 연결 전까지 `null`이다. 리뷰 수정일이나 게임 수집일을 대신 넣지 않는다.
- 데이터 버전 기능은 보류하며 `dataVersion`은 반환하지 않는다.
- 목록의 `meta`는 `period`, `timezone`, `aggregationBasis`, `lastCollectedAt`이다.
- 대표 리뷰의 `data`는 `meta`와 `items`이고, `meta`에는 위 필드에 `dataStatus: "AVAILABLE"`이 추가된다.
- `koreana`는 응답에서 `korean`으로 변환한다. `reviewDate`는 수정 시각의 KST 날짜다.
- `items[].body`는 저장된 `review_text`에 [Steam 본문 일반 텍스트 변환](conventions.md#steam-본문-일반-텍스트-변환)을 적용한다.
  DB 원문은 수정하지 않으며, 본문이 빈 문자열로 변환되어도 리뷰 행·건수·정렬은 유지한다.
- 커서는 게임·선택 토픽·조회 종료일과 도움됨 수·리뷰 ID를 포함한다. 다른 조건의 커서는 `400 INVALID_REQUEST`다.
- 목록의 `totalCount`는 커서 뒤 건수가 아니라, 같은 기간·토픽 조건을 만족하는 전체 리뷰 수다.
- 없는 게임은 `404 GAME_NOT_FOUND`, 잘못된 토픽·커서·limit는 `400 INVALID_REQUEST`다.

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

**Processing Rules / Notes — 정렬**

- 도움됨 수(`votes_up`) 내림차순으로 반환한다.
- 도움됨 수가 같으면 `review_id` 내림차순으로 순서를 고정한다.

**Error Responses**

- `400`: 토픽 ID 또는 페이지 커서 오류
- `401`
- `404`

## 리뷰 번역

### `GET /reviews/{reviewId}/translation`

[리뷰 번역 원본 명세](https://app.notion.com/p/3df776ebfd68812e996ecd6ae5c888ee)

**Auth**

- Required (`Authorization: Bearer {ACCESS_TOKEN}`)

**Path Variables**

| Name | Type | Description |
|---|---|---|
| `reviewId` | long | 리뷰 ID |

**Query Parameters**: 없음

**Request Body**: 없음

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-18 13:20:00",
  "data": {
    "reviewId": 12345,
    "translatedText": "최근 패치 이후 무기 밸런스가 크게 나빠졌습니다."
  },
  "success": true
}
```

`data.reviewId`는 `long`, `data.translatedText`는 `string`이다.

**Processing Rules / Notes — Rules**

- `reviewId`로 서버에 저장된 리뷰 원문을 조회한다.
- `recent_review.review_id` 기본키에 해당하는 행의 `review_text`를 사용한다. 다른 수정 버전으로 대체하거나 목록의 최근 14일 필터를 적용하지 않는다.
- 번역 대상 언어는 한국어로 고정하며, 외부 번역 API는 DeepL을 사용한다.
- 클라이언트에서 번역할 원문을 직접 전달하지 않는다. 서버에 존재하는 리뷰만 번역한다.
- 원문 자체가 빈 문자열·공백뿐이면 DeepL을 호출하지 않고 원문 그대로 `200`을 반환한다.
- 그 외 본문은 [Steam 본문 일반 텍스트 변환](conventions.md#steam-본문-일반-텍스트-변환)을 적용한다.
  DB의 `language_code`가 `korean` 또는 `koreana`이거나 변환 후 본문이 비어 있으면 DeepL 호출 없이 변환 결과를 반환한다.
- 나머지는 변환된 본문을 DeepL의 언어 자동 감지로 한국어(`KO`) 번역한다.
- DeepL 키 미설정·인증 오류, 미지원 언어, 입력 한도 초과, 할당량 초과, 연결 실패·시간 초과 및 잘못된 응답은 `502 TRANSLATION_UNAVAILABLE`로 처리한다.
- DeepL JSON 요청 본문의 UTF-8 크기가 128 KiB를 초과하면 외부 호출 없이 같은 `502`로 처리한다. 원문을 자르거나 분할하지 않고 자동 재시도하지 않는다.
- 번역 결과를 캐시하거나 DB에 저장하지 않는다. 각 요청에서 조회한 원문에 위 변환을 적용한다.

**Error Responses**

- `401 UNAUTHORIZED`: 인증이 필요합니다.
- `404 REVIEW_NOT_FOUND`: 리뷰를 찾을 수 없습니다.
- `502 TRANSLATION_UNAVAILABLE`: 번역 서비스를 이용할 수 없습니다.
- `500 INTERNAL_SERVER_ERROR`: 서버 내부 오류가 발생했습니다.

오류 응답은 [공통 오류 계약](conventions.md#error-response)에 따라 `code`, `message`, `responsedAt`을
사용하며, 원본 명세의 `success: false`는 포함하지 않는다. DB 등 일반 서버 오류는 DeepL 오류로 변환하지 않는다.

DeepL 입력 크기·언어 감지 기준: [공식 번역 API 문서](https://developers.deepl.com/api-reference/translate/request-translation).

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
- 최대 4건을 반환한다. 대상 리뷰가 4건 미만이면 있는 만큼 반환한다.
- 도움됨 수(`votes_up`) 내림차순으로 선정하고, 동률이면 `review_id` 내림차순으로 순서를 고정한다.
- 2026-09-15 사용자 결정 반영: 기존 명세의 최대 3건을 4건으로 변경했다.

**Error Responses**

- `401`
- `404`
