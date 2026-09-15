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

**오류 코드**

| HTTP 상태 | code | message |
|---|---|---|
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. |
| `404` | `GAME_NOT_FOUND` | 게임을 찾을 수 없습니다. |
| `404` | `MY_GAME_NOT_REGISTERED` | 내 게임에 등록되지 않은 게임입니다. |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. |

**Processing Rules / Notes**

- 인증된 현재 회원 ID와 `gameId`(DB의 `appid`)로 `my_game` 등록 관계를 삭제한다. 다른 회원의 등록과 게임 원본은 변경하지 않는다.
- 게임이 없으면 `GAME_NOT_FOUND`, 게임은 있지만 현재 회원의 등록 관계가 없으면 `MY_GAME_NOT_REGISTERED`를 반환한다. 이미 해제한 게임에 다시 요청해도 미등록 `404`다.
- 회원과 게임을 조건으로 삭제한 행 수로 해제 여부를 판정한다. 같은 등록에 동시 해제를 요청하면 실제 삭제한 요청만 성공하고 나머지는 미등록 `404`다.
- 성공 응답에는 `data`를 포함하지 않는다. 오류 응답과 인증 실패는 공통 계약을 따른다.
