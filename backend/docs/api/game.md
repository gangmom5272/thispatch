# game API

패키지: `com.ssafy.thispatch.domain.game` · [공통 규칙](conventions.md)

## 장르 목록 조회

### `GET /genres`

**Auth**

- Required

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "items": [
      {
        "id": 1,
        "name": "로그라이크"
      },
      {
        "id": 2,
        "name": "RPG"
      }
    ]
  },
  "success": true
}
```

**Error Responses**

- `401`: 인증 필요
- `503`: 장르 목록 조회 불가

## 게임 목록 조회

### `GET /games`

**Auth**

- Required

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `search` | string | No | 게임 제목 부분 검색, 최대 100자 |
| `sort` | string | No | `POSITIVE_RATE_ASC`(기본), `REVIEW_COUNT_DESC`, `REACTION_CHANGE_DESC`, `RELEASE_DATE_DESC` |
| `limit` | int | No | 기본 10, min 1, max 100 |
| `cursor` | string | No | 다음 페이지 조회용 커서 |
| `genreIds` | int[] | No | 콤마 구분, 생략 시 전체 |

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "items": [
      {
        "id": 730,
        "capsuleImageUrl": "https://example.com/images/game_1.jpg",
        "title": "샘플 게임",
        "tags": [
          {
            "id": 1,
            "name": "로그라이크"
          }
        ],
        "positiveRate": 70,
        "isMine": true,
        "gameSummary": {
          "id": 730,
          "title": "샘플 게임",
          "headerImageUrl": "https://example.com/images/game_2.jpg",
          "releasedOn": "2026-09-09",
          "developer": "샘플 개발사",
          "playModes": [
            "EA Dice",
            "멀티플레이"
          ],
          "description": "대규모 전장에서 차량과 분대 전투가 벌어지는 FPS입니다. 출시 초기 서버 안정성과 클래스 개편이 평가를 크게 흔들었습니다.",
          "userTags": [
            "FPS",
            "멀티플레이어",
            "전쟁",
            "슈터"
          ],
          "reviewCount": 220000,
          "latestPatch": "Update 7.4v"
        }
      }
    ],
    "page": {
      "limit": 20,
      "nextCursor": null,
      "hasNext": false,
      "totalCount": 1
    }
  },
  "success": true
}
```

`items[].id`는 `long`.

**Processing Rules / Notes — Field rules**

| Field | Type | Description |
|---|---|---|
| `items[].gameSummary.id` | long | 게임 식별자 |
| `items[].gameSummary.title` | string | 게임 제목 |
| `items[].gameSummary.headerImageUrl` | string | 게임 헤더 이미지 URL |
| `items[].gameSummary.releasedOn` | date? | 출시일 |
| `items[].gameSummary.developer` | string | 개발사 |
| `items[].gameSummary.playModes` | string[] | 플레이 모드 |
| `items[].gameSummary.description` | string? | 게임 요약 설명 |
| `items[].gameSummary.userTags` | string[] | 게임 태그 |
| `items[].gameSummary.reviewCount` | integer? | 전체 리뷰 수 |
| `items[].gameSummary.latestPatch` | string | 최신 패치명 |

**Error Responses**

- `400`: 검색 조건 또는 페이지 커서 오류
- `401`: 인증 필요

**Processing Rules / Notes — 프론트 사용**

자동완성도 별도 API 없이 다음처럼 재사용 가능하다.

```text
GET /games?search=slay&limit=5
```

## 현재 게임 정보

### `GET /games/{gameId}`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "id": 1,
    "capsuleImageUrl": "https://example.com/images/game_1.jpg",
    "title": "Slay the Spire 2",
    "tags": [
      {
        "id": 1,
        "name": "로그라이크"
      }
    ],
    "positiveRate": 70,
    "isMine": true,
    "description": "층을 오르며 덱을 구성하는 게임입니다.",
    "releasedOn": "2025-03-18",
    "reviewCount": 1000,
    "lastCollectedAt": "2026-09-09T08:00:00Z"
  },
  "success": true
}
```

**Processing Rules / Notes — Field rules**

| Field | Type |
|---|---|
| `id` | long |
| `capsuleImageUrl` | string? |
| `title` | string |
| `description` | string? |
| `tags` | `{id:int, name:string}[]` |
| `releasedOn` | date? |
| `reviewCount` | integer? |
| `positiveRate` | number? |
| `lastCollectedAt` | datetime? |
| `isMine` | boolean |

**Error Responses**

- `401`: 인증 필요
- `404`: 게임 없음

> 사례 게임 hover에서도 별도 preview API를 만들지 않고 이 API를 재사용한다.

## 내 게임 등록

### `POST /games/{gameId}/my-game`

**Auth**

- Required

**Path Variables**

| Name | Type |
|---|---|
| `gameId` | long |

**Request Body**: 없음

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2025-04-21 17:08:43",
  "success": true
}
```

**Error Responses**

- `401`: 로그인 필요
- `404`: 게임 없음
- `409`: 이미 내 게임으로 등록됨

## 내 게임 등록 해제

### `DELETE /games/{gameId}/my-game`

**Auth**

- Required

**Headers**

| Name | Type | Description | Example |
|---|---|---|---|
| `Authorization` | string | 필수. 로그인 사용자 식별 | `Bearer {ACCESS_TOKEN}` |

**Path Variables**

| Name | Type | Description |
|---|---|---|
| `gameId` | long | 게임 ID |

**Query Parameters**: 없음

**Request Body**: 없음

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-14 15:12:00",
  "success": true
}
```

**Error Responses**

- `401`: 로그인 필요
- `404`: 게임 없음
- `404`: 내 게임에 등록되지 않은 게임
- `500`: 서버 내부 오류
