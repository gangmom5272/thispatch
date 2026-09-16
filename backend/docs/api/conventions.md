# API Conventions

## 계약 규칙

1. 이 문서에 명시된 **Method / URL / 필드명 / 타입 / 인증 요구 / 주요 상태 코드**를 API 계약으로 본다.
2. 프론트 계약을 임의로 정리하거나 REST 스타일에 맞추기 위해 엔드포인트나 필드명을 바꾸지 않는다.
3. `responsedAt` 철자는 기존 계약을 유지한다.
4. API의 게임 식별자는 `gameId`, DB에서는 `appid`를 사용한다.
5. `gameId` 타입은 `long`.
6. API/UI에서는 `genre`, DB에서는 `tag`, `game_tag`를 사용한다.
7. 일반 서비스 API의 인증 방식은 JWT Bearer Token이며, 로그인 필수 API는 `Authorization: Bearer {ACCESS_TOKEN}`을 요구한다.
8. `/session`만 Authorization 선택이다.

## Auth

| 표기 | 의미 |
|---|---|
| Required | 인증 필수 |
| Optional | 인증 선택 |
| None | 인증 요구 없음 |

- Access Token 인증 없이 접근하는 API: `POST /auth/login`, `POST /auth/signup`, `GET /auth/steam/login`, `GET /auth/steam/callback`, `POST /auth/steam/token`, `POST /auth/refresh`.
- `GET /session`은 Authorization 선택이며, `POST /auth/steam/signup`(최초 닉네임 설정), `POST /auth/logout`과 각 도메인에서 Required로 표기한 API는 인증 필수다.
- 자체 로그인·회원가입 및 Steam 로그인 토큰 교환 성공 시 Access Token과 Refresh Token은 Response Body의 `data.accessToken`, `data.refreshToken`으로 반환한다. HttpOnly Cookie로 전달하지 않는다. Steam 최초 닉네임 설정에서는 토큰을 발급하지 않는다.
- Steam 콜백은 신규 회원을 닉네임 없이 먼저 생성하고, 신규·기존 회원 모두 `302 Redirect`로 1회용 `loginCode`를 동일한 프론트 콜백에 전달한다. Access Token과 Refresh Token은 Redirect URL에 포함하지 않는다.
- `loginCode`는 로그인 토큰 교환 전용이며 일반 API 인증에 사용할 수 없다.
- Steam 로그인 토큰 교환 응답의 `data.nickname`, `/session` 응답의 `data.user.nickname`은 `string | null`이다. 프론트는 닉네임이 `null`이면 닉네임 설정 화면으로 이동하며, 별도의 `onboardingRequired` 필드는 사용하지 않는다.
- Refresh Token은 `POST /auth/refresh`, `POST /auth/logout`의 Request Body로 전달한다. 재발급 응답은 기존 계약대로 새 Access Token을 반환한다.

## Response

- 성공 응답의 공통 envelope: `code`, `message`, `responsedAt`, `data`, `success`. `code`는 예시의 문자열 표현을 유지한다.
- `data` 없는 성공 응답, `data` 또는 `summary`만 제시된 예시, 단일 Response item은 각 명세 그대로 사용한다. 누락 필드를 임의로 채우거나 구조를 통일하지 않는다.
- 오류 응답은 아래 공통 형식을 사용한다. endpoint별 Error Responses의 상태 코드·설명은 유지한다.
- Steam 로그인 시작·콜백의 `302 Redirect` 응답은 JSON envelope 없이 `Location` 헤더를 사용한다. Steam 인증 실패 콜백도 회원 API 명세의 실패 Redirect를 따른다.

## Error Response

일반 오류 응답은 `code`, `message`, `responsedAt`만 포함한다. `data`, `success`는 사용하지 않는다.

| 필드 | 타입 | 의미 |
|---|---|---|
| `code` | string | HTTP 상태 코드와 별도로 오류 원인을 구분하는 문자열 코드 |
| `message` | string | 사용자에게 표시할 수 있는 오류 메시지 |
| `responsedAt` | string | `Asia/Seoul` 기준 응답 생성 시각, `yyyy-MM-dd HH:mm:ss` |
| `errors` | array, 선택 | 필드 검증·바인딩 실패의 상세 목록. 상세 항목이 없으면 필드 자체를 생략 |
| `errors[].field` | string | 요청 DTO 필드 경로 또는 요청 파라미터 이름 |
| `errors[].message` | string | 해당 필드의 오류 설명 |

예상하지 못한 서버 오류는 HTTP `500`과 아래 응답을 반환한다. 예외 상세와 스택 트레이스는 서버 로그에만 기록한다.

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "서버 내부 오류가 발생했습니다.",
  "responsedAt": "2026-09-14 15:30:00"
}
```

입력값 검증 실패는 HTTP `400`으로 응답하며, 필드별 오류가 있으면 `errors`를 포함한다.
입력값 자체나 `rejectedValue`, Java 타입, 예외 이름은 응답에 포함하지 않는다.
검증 메시지를 작성할 때도 비밀번호·토큰 등 입력값을 메시지에 삽입하지 않는다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해주세요.",
  "responsedAt": "2026-09-14 15:30:00",
  "errors": [
    {
      "field": "email",
      "message": "올바른 이메일 형식이 아닙니다."
    }
  ]
}
```

### 공통 코드와 적용 범위

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 입력값을 확인해주세요. | 요청 DTO 및 컨트롤러 파라미터의 검증 실패, DTO 바인딩 실패 |
| `400` | `INVALID_REQUEST` | 올바르지 않은 요청입니다. | 잘못된 JSON, 필수 본문·파라미터 누락, 파라미터 타입 오류 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. | 예상하지 못한 예외 및 반환값 검증 실패 등 서버 오류 |

- 이 표는 공통 처리 기반의 코드다. 도메인별 비즈니스 오류는 해당 API 구현 시 기존 오류 상태 코드·설명에 맞춰 정의하고 문서화한다.
- 잘못된 경로·HTTP Method·Content-Type 등 Spring MVC 자체 오류는 기존 HTTP 상태와 헤더를 유지한다. 위 `400`·`500` 이외에는 상태의 이름을 코드로 사용한다(예: `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`). 메시지는 `요청을 처리할 수 없습니다.`이며, `5xx`는 `서버 내부 오류가 발생했습니다.`를 사용한다. 표준 상태 이름이 없는 경우 코드는 `HTTP_ERROR`다.
- 객체 전체에 대한 검증 실패처럼 필드를 특정할 수 없는 경우에는 `VALIDATION_FAILED`와 공통 메시지만 반환한다. `errors`의 항목 순서는 보장하지 않는다.
- 비즈니스 오류는 `global.exception.ErrorCode`를 구현한 코드와 `BusinessException`으로 연결한다. 예외 원인의 메시지를 응답 메시지로 사용하지 않는다.
- `GlobalExceptionHandler`는 Spring MVC 영역에 적용한다. Spring Security 필터의 인증·권한 오류는 `SecurityErrorHandler`가 같은 `ErrorResponse` 형식으로 반환한다.

### Security 인증·권한 오류

| HTTP 상태 | code | message | 적용 상황 |
|---|---|---|---|
| `401` | `UNAUTHORIZED` | 인증이 필요합니다. | 인증 필수 요청에 인증된 사용자가 없음 |
| `403` | `FORBIDDEN` | 접근 권한이 없습니다. | 인증된 사용자가 Security 권한 검사에서 거부됨 |

- `401` 응답은 `WWW-Authenticate: Bearer` 헤더를 포함하며 로그인 페이지로 Redirect하지 않는다.
- 응답에는 `code`, `message`, `responsedAt`만 포함한다. 토큰·자격 증명·예외 상세는 포함하지 않는다.
- 자체 로그인 자격 증명 불일치, Steam 로그인 코드 오류, Refresh Token 오류 등 API별 비즈니스 오류는 각 endpoint의 계약을 유지한다.
- 현재 접근 규칙은 공개 endpoint의 HTTP Method·URL과 `GET /session`을 허용하고, 나머지 요청에 인증을 요구한다. 역할별 권한 규칙은 추가하지 않는다.
- `POST /auth/steam/signup`은 URL의 `/auth` 접두어와 관계없이 인증 필수다. 구현 시 Security 공개 경로 및 JWT 필터 제외 목록에서 제거해야 한다.
- 서버 내부 `ERROR` dispatch는 원래 오류 처리를 위해 허용한다. 클라이언트가 직접 보내는 `/error` 요청은 공개하지 않는다.
- Bearer 인증을 기준으로 HTTP 세션 인증, form login, HTTP Basic, Security 기본 로그아웃과 요청 저장을 사용하지 않는다. CSRF 필터는 비활성화하며, CORS는 기존 서블릿 필터가 처리한다.
- `JwtAuthenticationFilter`는 Authorization 헤더의 Access Token을 검증하고 회원 ID를 담은 `MemberPrincipal`을 SecurityContext에 등록한다. Refresh Token은 일반 API 인증에 사용할 수 없다.
- 인증 필수 경로의 토큰 누락·만료·무효, Bearer 형식 오류·중복 Authorization 헤더는 `401 UNAUTHORIZED`로 처리한다. 토큰은 Query Parameter나 Cookie에서 읽지 않는다.
- `GET /session`은 토큰 누락 또는 정상 Access Token의 만료 시 인증 없이 통과한다. 위조·형식 오류·용도 불일치 등 나머지 오류는 `401 UNAUTHORIZED`다. 로그인 상태 응답과 닉네임 조회는 회원 API에서 처리한다.
- Auth가 None인 공개 endpoint에서는 Access Token 헤더를 검사하지 않는다. 로그인·회원가입·갱신 등 각 API가 자체 자격 증명과 Request Body를 검증한다.
- JWT 자체의 유효성 검증 후, 인증 필수 요청은 매번 DB에서 회원 존재와 `status = ACTIVE` 여부를 확인한다. 회원 부재·탈퇴 등 비활성 상태는 `401 UNAUTHORIZED`이며, DB 조회·트랜잭션 장애는 인증 오류로 바꾸지 않고 공통 `500 INTERNAL_SERVER_ERROR`로 반환한다. 상태를 캐시하지 않으므로 탈퇴 커밋 후 기존 Access Token도 보호 API에서 즉시 거부된다.
- 공개 인증 API와 `GET /session`은 이 보호 API 상태 검사를 적용하지 않고 각 endpoint의 기존 회원 상태·오류 계약을 유지한다. 토큰 없음·무효·만료 시에는 상태 조회를 하지 않는다. 이미 상태 검사를 통과한 진행 중 요청을 취소하지는 않는다.
