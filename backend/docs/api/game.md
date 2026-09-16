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

**Processing Rules / Notes**

- Path Variable, Query Parameter, Request Body는 없다.
- `tag` 테이블 전체를 반환한다. `game_tag` 연결 여부로 필터링하지 않는다.
- `tag.tag_id`를 `items[].id`(int), `tag.name_ko`를 `items[].name`(string)으로 매핑한다. 두 필드는 null이 아니다.
- `tag_id` 오름차순으로 반환하며 pagination은 적용하지 않는다.
- 조회 결과가 없으면 `200`과 `data.items: []`를 반환한다.
- 장르 DB 조회 및 해당 읽기 트랜잭션의 시작·종료 실패는 `503 GENRE_LIST_UNAVAILABLE`로 반환한다.
- 인증 단계의 회원 상태 조회 장애와 예상하지 못한 코드 오류는 기존 공통 `500 INTERNAL_SERVER_ERROR`로 처리한다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `503` | `GENRE_LIST_UNAVAILABLE` | 장르 목록을 조회할 수 없습니다. | 장르 DB 조회·트랜잭션 실패 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | 인증 단계의 DB 장애 및 예상하지 못한 서버 오류 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다.

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

**Processing Rules / Notes — 조회·매핑**

- Query Parameter, Request Body는 없다. `gameId`는 `game.appid`에 대응한다.
- `id`, `title`, `description`은 각각 `game.appid`, `game.name`, `game.short_description`을 반환한다.
- `capsuleImageUrl`은 `https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/{gameId}/{capsule_path}`로 조합한다. `capsule_path`의 하위 경로를 유지하며, 값이 없으면 `null`이다. 대체 이미지는 사용하지 않는다.
- `reviewCount`, `positiveRate`는 각각 `game.store_review_count`, `game.store_positive_pct`를 반환한다. 스토어에서 수집한 통계이며 리뷰 배치 집계로 대체하거나 재계산하지 않는다. `positiveRate`의 단위는 퍼센트(0~100)다.
- `releasedOn`은 `game.release_ts`를 `Asia/Seoul` 기준 날짜(`yyyy-MM-dd`)로 변환한다.
- `lastCollectedAt`은 게임 카탈로그 수집 시각인 `game.collected_at`을 UTC ISO 8601 형식으로 반환한다. 리뷰 수집·집계 시각을 뜻하지 않는다.
- nullable 필드는 값이 없으면 JSON `null`로 포함한다. 리뷰 수·긍정률의 `null`과 실제 `0`은 구분한다.
- `tags`는 해당 게임의 `game_tag`와 `tag`를 연결하여 `tag.tag_id`, `tag.name_ko`를 반환한다. 연결된 전체 태그를 `game_tag.weight` 내림차순, 동률이면 `tag.tag_id` 오름차순으로 정렬한다. 태그가 없으면 `[]`다.
- `isMine`은 검증된 Access Token의 `MemberPrincipal.memberId`와 `gameId`에 해당하는 `my_game` 등록 관계의 존재 여부다. 클라이언트가 전달한 회원 ID는 사용하지 않는다.
- 저장된 게임을 조회하며 요청 중 Steam API 호출이나 데이터 갱신은 수행하지 않는다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | `gameId`가 long으로 변환되지 않음 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `404` | `GAME_NOT_FOUND` | 게임을 찾을 수 없습니다. | `game.appid`에 해당 게임이 없음 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 조회·트랜잭션 실패 및 예상하지 못한 서버 오류 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다.

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

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | `gameId`가 long으로 변환되지 않음 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 유효한 Access Token 또는 활성 회원 인증 없음 |
| `404` | `GAME_NOT_FOUND` | 게임을 찾을 수 없습니다. | `game.appid`에 해당 게임이 없음 |
| `409` | `MY_GAME_ALREADY_REGISTERED` | 이미 내 게임으로 등록된 게임입니다. | 현재 회원에게 이미 등록된 게임 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | 예상하지 못한 DB·서버 오류 |

**Processing Rules / Notes**

- 등록 회원은 검증된 Access Token의 `MemberPrincipal.memberId`로 식별한다. 클라이언트가 전달한 회원 ID는 사용하지 않는다.
- `gameId`는 `game.appid`에 대응한다. 게임 존재 여부를 확인한 뒤 `my_game`에 현재 회원 ID, 게임 ID와 등록 시각(`created_at`)을 저장한다.
- `(member_id, appid)` 기본키를 기준으로 중복을 판정한다. 동일 회원·게임의 동시 등록은 한 요청만 `200`으로 성공하고 나머지는 `409`를 반환한다.
- 중복 요청은 기존 등록과 `created_at`을 변경하지 않는다. 다른 회원은 같은 게임을 각각 등록할 수 있다.
- 등록은 하나의 DB 트랜잭션으로 처리한다. 중복 기본키 충돌 이외의 DB 오류를 중복 등록 오류로 바꾸지 않는다.
- 성공 응답에는 `data`를 포함하지 않으며, 응답 시각과 오류 응답은 공통 계약을 따른다.

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
