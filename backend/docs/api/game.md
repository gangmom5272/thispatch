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

## 전체 게임 목록 조회

### `GET /games`

**Auth**

- Required

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `search` | string | No | 게임 제목 부분 검색, 최대 100자 |
| `sort` | string | No | `POSITIVE_RATE_ASC`(기본), `REVIEW_COUNT_DESC`, `REACTION_CHANGE_DESC`, `RELEASE_DATE_DESC` |
| `limit` | int | No | 기본 10, min 1, max 100 |
| `cursor` | string | No | 전체 게임 목록의 다음 페이지 조회용 커서. 첫 조회 시 생략 |
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
          "headerImageUrl": "https://example.com/images/game_1.jpg",
          "releasedOn": "2026-09-09",
          "developer": "샘플 개발사",
          "playModes": [
            "멀티플레이어",
            "싱글 플레이어"
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
| `items[].gameSummary.headerImageUrl` | string? | capsuleImageUrl과 같은 이미지 URL |
| `items[].gameSummary.releasedOn` | date? | 출시일 |
| `items[].gameSummary.developer` | string? | 개발사 |
| `items[].gameSummary.playModes` | string[] | 플레이 모드 |
| `items[].gameSummary.description` | string? | 게임 요약 설명 |
| `items[].gameSummary.userTags` | string[] | 게임 태그 |
| `items[].gameSummary.reviewCount` | integer? | 전체 리뷰 수 |
| `items[].gameSummary.latestPatch` | string? | 최신 패치명 |

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | 검색 길이·limit 범위 검증 실패 (필드별 errors 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | sort·limit 타입, 장르 입력, 커서 형식·조건 오류 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 조회·트랜잭션 장애 및 예상하지 못한 서버 오류 |

**Processing Rules / Notes — 조회·검색·정렬·커서**

- Path Variable, Request Body는 없다. 저장된 전체 `game`을 조회하며 요청 중 외부 API 호출·수집·갱신은 하지 않는다.
- 검색은 입력 길이 최대 100자를 검증한 후 앞뒤 공백을 제거하고 대소문자를 무시하는 제목 부분 일치로 처리한다. 빈 검색은 전체 조회다. 내부 공백과 `%`, `_`, `\\` 등 특수문자는 입력 문자 그대로 검색한다.
- `genreIds`는 양의 int ID를 콤마로 구분한다. 빈 값·빈 항목·숫자 형식/범위 오류는 400이다. 중복은 제거하고, 여러 장르는 하나라도 연결되면 포함하는 OR 조건이다. 존재하지 않는 ID는 일치하지 않으며, 일치 게임이 없으면 빈 목록이다.
- `POSITIVE_RATE_ASC`는 `game.store_positive_pct` 오름차순, `REVIEW_COUNT_DESC`는 `game.store_review_count` 내림차순, `RELEASE_DATE_DESC`는 `release_ts`를 KST 날짜로 변환한 출시일 내림차순이다. 모든 정렬은 null을 마지막에 두며, 동률은 `appid` 오름차순이다.
- `REACTION_CHANGE_DESC`는 각 게임의 최신 패치에 연결된 `patch_stat.delta_pct`의 절댓값 내림차순이다. 최신 패치는 `news.is_patch = true` 중 `published_ts` 내림차순, 동률이면 `gid` 문자열 내림차순으로 하나를 선택한다. 최신 패치에 통계가 없거나 `delta_pct`가 null이면 정렬값도 null이다. 이전 패치 통계로 대체하지 않는다.
- 기존 패치 집계의 비교 기간은 패치 게시일 D(KST) 기준 이전 `[D-7일, D)`, 이후 `[D, D+7일)`이다. 각 구간의 리뷰 수정 시각 기준 최종 관측으로 계산한 긍정률의 차이(이후 - 이전, %p)를 사용하며 목록 API에서 다시 계산하지 않는다.
- 커서는 버전, 전체 목록 구분, 정규화한 검색·장르·정렬 조건, 마지막 정렬값과 게임 ID를 담는 Base64URL JSON이다. 클라이언트는 응답 커서를 그대로 전달한다. 잘못된 형식·내용, 조건이 다른 커서, 내 게임 목록 커서는 `400 INVALID_REQUEST`다. `limit` 변경은 허용하며 장르 순서·중복과 검색 대소문자·앞뒤 공백 차이는 같은 조건이다.
- 마지막 정렬값·ID 다음부터 조회하는 keyset 페이지네이션을 사용한다. 커서의 게임이 삭제되어도 저장된 경계값으로 계속 조회한다.
- 각 요청 안에서는 전체 건수와 목록·부가 정보를 동일한 읽기 스냅샷에서 조회한다. 페이지 간에는 최신 DB를 조회하며 결과를 고정해 저장하지 않는다. 페이지 사이 데이터 삽입·삭제·정렬값 갱신으로 게임이 중복되거나 누락될 수 있다. 조건 변경 시 커서를 버리고 첫 페이지부터 조회한다.
- `page.limit`는 실제 요청값(생략 시 10), `page.totalCount`는 커서 이전도 포함하여 검색·장르 필터에 맞는 전체 게임 수(long)다. 등록 게임도 포함하며 `my_game`으로 개수를 제한하지 않는다.
- 빈 결과는 `200`, `items: []`, `hasNext: false`, `nextCursor: null`이다. 마지막 페이지도 `hasNext: false`, `nextCursor: null`이다. 다음 페이지가 있을 때만 마지막 반환 게임을 기준으로 커서를 발급한다.

**Processing Rules / Notes — 목록·요약 필드 매핑**

- `items[].id/title` 및 `gameSummary.id/title`은 `game.appid/name`이다. `isMine`은 인증된 `MemberPrincipal.memberId`의 `my_game` 등록 관계 존재 여부다.
- `items[].capsuleImageUrl`과 `gameSummary.headerImageUrl`은 같은 capsule 이미지 URL이다. `https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/{appid}/{capsule_path}`로 조합하고 하위 경로를 유지한다. 저장 경로가 null·빈 문자열·공백이면 두 필드 모두 null이며 대체 이미지나 별도 헤더 이미지 수집은 사용하지 않는다.
- `positiveRate`는 `game.store_positive_pct`(0~100 퍼센트, number?), `gameSummary.reviewCount`는 `game.store_review_count`(integer?)다. null과 실제 0을 구분한다.
- `gameSummary.releasedOn`은 `game.release_ts`를 `Asia/Seoul` 날짜로 변환하고, `description`은 `game.short_description`, `developer`는 `game.developer`다. 없는 개발사(null·빈 문자열·공백)는 null이다.
- `tags`는 연결된 전체 `game_tag/tag`의 `tag_id/name_ko`를 가중치 내림차순·태그 ID 오름차순으로 반환한다. `gameSummary.userTags`는 같은 순서의 전체 태그 이름이다.
- `gameSummary.playModes`는 V8의 `game_play_mode/play_mode`를 연결한 `name_ko`를 `play_mode_id` 오름차순으로 반환한다. 개발사나 일반 기능 카테고리는 포함하지 않는다.
- `gameSummary.latestPatch`는 위 기준으로 선택한 최신 패치의 `news.title`이다. 패치가 없거나 제목이 빈 문자열·공백이면 null이다.
- nullable 필드는 JSON null로 포함한다. 태그·플레이 모드가 없으면 각 배열은 `[]`다. 새 schema/migration은 추가하지 않는다.

**Processing Rules / Notes — 프론트 사용**

- 전체 게임을 조회하며 현재 사용자가 등록한 게임도 포함한다. `items[].isMine`으로 현재 사용자의 내 게임 등록 여부를 반환한다.
- 검색·정렬·장르 필터는 전체 게임 집합에 적용한다.
- 내 게임 목록은 `GET /members/me/games`로 별도 조회한다. 전체 게임 목록의 `items`를 `isMine`으로 나눈 결과를 내 게임 목록으로 사용하지 않는다.
- 두 API의 커서와 페이지 상태(`limit`, `nextCursor`, `hasNext`, `totalCount`)는 독립적으로 관리한다.
- `items[].gameSummary`는 목록 응답에 유지한다.

자동완성도 별도 API 없이 다음처럼 재사용 가능하다.

```text
GET /games?search=slay&limit=5
```

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다. 오류 응답에 `data`, `success`를 포함하지 않는다.

## 내 게임 목록 조회

### `GET /members/me/games`

**Auth**

- Required
- `Authorization: Bearer {ACCESS_TOKEN}`으로 현재 로그인 사용자를 식별한다.

**Path Variables / Request Body**: 없음

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `search` | string | No | 내 게임 중 게임 제목 부분 검색, 최대 100자 |
| `sort` | string | No | `POSITIVE_RATE_ASC`(기본), `REVIEW_COUNT_DESC`, `REACTION_CHANGE_DESC`, `RELEASE_DATE_DESC` |
| `limit` | int | No | 기본 10, min 1, max 100 |
| `cursor` | string | No | 내 게임 목록의 다음 페이지 조회용 커서. 첫 조회 시 생략 |
| `genreIds` | int[] | No | 내 게임에 적용할 장르 ID를 콤마로 구분. 생략 시 장르 제한 없음 |

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-17 09:39:00",
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
        "gameSummary": {
          "id": 730,
          "title": "샘플 게임",
          "headerImageUrl": "https://example.com/images/game_2.jpg",
          "releasedOn": "2026-09-09",
          "developer": "샘플 개발사",
          "playModes": [
            "싱글 플레이어",
            "멀티플레이어"
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

**Processing Rules / Notes**

- `items[].id`는 `long`이다.
- 검증된 Access Token의 `MemberPrincipal.memberId`를 기준으로 현재 사용자가 `my_game`에 등록한 게임만 반환한다. 클라이언트가 전달한 회원 ID는 사용하지 않는다.
- 검색·정렬·장르 필터는 현재 사용자의 `my_game`에 등록된 게임 집합에만 적용한다.
- 각 요청은 그 요청의 조회 시점에 커밋된 내 게임 등록 상태를 사용한다. 페이지 사이에 등록·해제한 내용은 다음 조회부터 반영하며, 첫 페이지의 게임 집합을 이후 페이지까지 고정하지 않는다.
- 한 응답의 `items`와 `totalCount`는 동일한 DB 스냅샷을 기준으로 조회한다. 조회가 시작된 뒤 커밋된 등록·해제는 다음 요청에 반영될 수 있다.
- 페이지 이동 중 새 게임이 이미 지나간 정렬 위치에 등록되면 첫 페이지를 새로 조회해야 확인할 수 있다. 이전 커서의 기준 게임을 등록 해제해도 저장된 정렬 경계로 다음 페이지를 조회한다.
- 등록한 게임이 없거나 검색·필터·커서 이후의 결과가 없으면 `200`, `items: []`, `nextCursor: null`, `hasNext: false`를 반환한다. `totalCount`는 현재 회원의 검색·장르 필터에 맞는 전체 등록 게임 수이며 커서 이전의 게임도 포함한다.
- 내 게임 목록이므로 `items[].isMine` 필드는 반환하지 않는다.
- `items[].gameSummary`는 전체 게임 목록과 동일하게 포함하며, 하위 필드의 타입과 의미도 전체 게임 목록의 Field rules를 따른다.
- 이 API의 커서와 페이지 상태(`limit`, `nextCursor`, `hasNext`, `totalCount`)는 전체 게임 목록 조회 API와 독립적으로 관리한다.
- 검색 정규화·장르 OR 필터·정렬 동률/null 순서·최신 패치 선택·반응 변화 계산·목록과 요약 필드 매핑은 전체 게임 목록의 확정된 규칙을 동일하게 적용한다. 단, 조회 대상과 `totalCount`는 현재 회원의 등록 게임으로 제한하고 `isMine`은 생략한다.
- 커서는 내 게임 목록 종류와 인증된 회원 ID에 연결한다. 전체 게임 목록의 커서나 다른 회원의 커서, 검색·장르·정렬 조건이 다른 커서는 `400 INVALID_REQUEST`다. `limit` 변경은 허용한다.
- 프론트에서는 내 게임 카드의 등록 상태를 명시적으로 설정해야 한다. 두 목록의 로딩·페이지 상태를 독립적으로 관리하고 등록·해제 후 각각 새로 조회한다. 프론트 구현 변경은 이 API 작업에 포함하지 않는다.

**Error Responses**

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | 검색 길이·limit 범위 검증 실패 (필드별 errors 포함) |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | sort·limit 타입, 장르 입력, 커서 형식·목록·회원·조건 오류 |
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 없음·무효·만료 토큰·비활성 회원 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | DB 조회·트랜잭션 장애 및 예상하지 못한 서버 오류 |

오류 응답은 [공통 오류 계약](conventions.md#error-response)을 따른다. 오류 응답에 `data`, `success`를 포함하지 않는다.

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
