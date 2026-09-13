# API Conventions

## 계약 규칙

1. 이 문서에 명시된 **Method / URL / 필드명 / 타입 / 인증 요구 / 주요 상태 코드**를 API 계약으로 본다.
2. 프론트 계약을 임의로 정리하거나 REST 스타일에 맞추기 위해 엔드포인트나 필드명을 바꾸지 않는다.
3. `responsedAt` 철자는 기존 계약을 유지한다.
4. API의 게임 식별자는 `gameId`, DB에서는 `appid`를 사용한다.
5. `gameId` 타입은 `long`.
6. API/UI에서는 `genre`, DB에서는 `tag`, `game_tag`를 사용한다.
7. 로그인 필수 API는 `Authorization: Bearer {ACCESS_TOKEN}`을 요구한다.
8. `/session`만 Authorization 선택이다.

## Auth

| 표기 | 의미 |
|---|---|
| Required | 인증 필수 |
| Optional | 인증 선택 |
| None | 인증 요구 없음 |

## Response

- 공통 envelope: `code`, `message`, `responsedAt`, `data`, `success`. `code`는 예시의 문자열 표현을 유지한다.
- `data` 없는 성공 응답, `data` 또는 `summary`만 제시된 예시, 단일 Response item은 각 명세 그대로 사용한다. 누락 필드를 임의로 채우거나 구조를 통일하지 않는다.
- 공통 오류 JSON은 정의되어 있지 않다. endpoint별 Error Responses의 상태 코드·설명을 따르며, 오류 body·필드·상태 코드·설명을 임의로 추가하지 않는다.
