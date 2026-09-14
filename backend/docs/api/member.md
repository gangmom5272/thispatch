# member API

패키지: `com.ssafy.thispatch.domain.member` · [공통 규칙](conventions.md)

## 로그인 여부 / 닉네임 조회

### `GET /session`

**Auth**

- Optional

**Response 200 - 로그인 상태**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "authenticated": true,
    "user": {
      "id": 1,
      "nickname": "황용진"
    }
  },
  "success": true
}
```

`user.id`는 `long`.

**Response 200 - 비로그인 또는 세션 만료**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-09 17:08:43",
  "data": {
    "authenticated": false,
    "user": null
  },
  "success": true
}
```

**Error Responses**

- `503`: 세션 정보 조회 불가
- `401`: Access Token 위조·형식 오류·용도 불일치, Bearer 형식 오류·중복 Authorization 헤더 (`UNAUTHORIZED`, `인증이 필요합니다.`)

> `/session`은 로그인 수행 API가 아니라 현재 로그인 상태 확인용이다.

- Authorization 헤더가 없거나 정상 Access Token이 만료된 경우에는 위의 비로그인 `200` 응답을 사용한다. 그 외 잘못된 토큰은 `401`이며, Refresh Token도 일반 인증에 사용할 수 없다.

## 로그인

### `POST /auth/login`

**Auth**

- None

**Request Body**

```json
{
  "email": "user@example.com",
  "password": "password"
}
```

**Response 200**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2025-04-21 17:08:43",
  "data": {
    "accessToken": "xxxxxxx",
    "refreshToken": "ey….x"
  },
  "success": true
}
```

**Error Responses**

- `400`: 이메일 또는 비밀번호 형식 오류
- `401`: 이메일 또는 비밀번호 불일치
- `500`: 서버 내부 오류

## 회원가입

### `POST /auth/signup`

**Auth**

- None

**Request Body**

```json
{
  "email": "user@example.com",
  "password": "password",
  "nickname": "thispatch"
}
```

**Response 200**

```json
{
  "code": "200",
  "message": "회원가입에 성공했습니다.",
  "responsedAt": "2026-09-12 18:00:00",
  "data": {
    "accessToken": "issued-access-token",
    "refreshToken": "issued-refresh-token"
  },
  "success": true
}
```

**Error Responses**

- `400`: 이메일/비밀번호/닉네임 형식 오류
- `409`: 이미 가입된 이메일
- `500`: 서버 내부 오류

**Processing Rules / Notes — 구현 메모**

- 로컬 가입 시 서버 내부 값:
  - `login_type = LOCAL`
  - `status = ACTIVE`
  - `steam_id = null`
- 가입 완료 시 Access Token과 Refresh Token을 Response Body로 반환한다.

## Steam 로그인 시작

### `GET /auth/steam/login`

**Auth**

- None

**Query Parameters**: 없음

**Request Body**: 없음

**Response 302**

```http
HTTP/1.1 302 Found
Location: https://steamcommunity.com/openid/login?...
```

**Processing Rules / Notes — 구현 메모**

- 백엔드가 Steam OpenID 인증 URL을 생성하고 브라우저를 Steam 로그인 페이지로 이동시킨다.
- `return_to`는 `{BACKEND_PUBLIC_URL}/auth/steam/callback`을 사용한다.
- `realm`은 외부에서 접근 가능한 `BACKEND_PUBLIC_URL` 기준으로 설정한다.
- 프론트는 fetch/axios 대신 브라우저 자체를 이 API URL로 이동시킨다.
- 응답은 JSON이 아닌 `302 Redirect`다. URL 설정은 아래 Redirect URL 설정을 따른다.

## Steam 인증 콜백

### `GET /auth/steam/callback`

**Auth**

- None

**Query Parameters**

| Name | Type | Required | Description |
|---|---|---:|---|
| `openid.*` | string | 응답별 상이 | Steam OpenID가 전달하는 인증 결과 파라미터 집합. 실제 필수 항목은 OpenID 응답 유형에 따름 |

프론트가 직접 생성하거나 전달하는 값이 아니다. `openid.*`는 단일 파라미터 이름이 아닌 `openid.` 접두사를 사용하는 파라미터를 뜻한다.

**Request Body**: 없음

**Response 302 - 기존 회원**

```http
HTTP/1.1 302 Found
Location: {FRONTEND_BASE_URL}/auth/steam/callback?loginCode={LOGIN_CODE}
```

**Response 302 - 신규 회원**

```http
HTTP/1.1 302 Found
Location: {FRONTEND_BASE_URL}/signup/steam?signupToken={SIGNUP_TOKEN}
```

**Response 302 - Steam 인증 실패**

```http
HTTP/1.1 302 Found
Location: {FRONTEND_BASE_URL}/login?error=STEAM_AUTH_FAILED
```

**Processing Rules / Notes — 구현 메모**

1. Steam OpenID 응답을 백엔드에서 검증한다.
2. 검증된 `claimed_id`에서 Steam ID를 추출한다.
3. 해당 Steam ID로 `member`를 조회하고 기존 회원과 신규 사용자 흐름을 분기한다.

- 기존 회원에게는 해당 회원과 연결된 짧은 수명의 1회용 `loginCode`를 발급한다. 프론트는 `POST /auth/steam/token`으로 교환한다.
- 신규 사용자에게는 검증된 Steam ID와 서버 측에서 연결된 짧은 수명의 1회용 `signupToken`을 발급한다. 프론트는 닉네임 입력 후 `POST /auth/steam/signup`을 호출한다.
- `loginCode`는 로그인 토큰 교환에만, `signupToken`은 회원가입에만 사용한다. 두 값 모두 일반 API 인증에는 사용할 수 없다.
- Access Token과 Refresh Token은 Redirect URL에 포함하지 않는다.
- 신규 회원은 닉네임 제출 후 가입 완료 시점에 생성한다. 자체 가입 계정과 Steam 계정의 연결·병합은 지원하지 않는다.
- 탈퇴한 Steam 계정의 재가입 정책은 미정이다. 탈퇴 회원의 콜백 분기와 응답은 해당 정책 확정 후 정의한다.
- 응답은 JSON이 아닌 `302 Redirect`다. TTL 및 Redirect URL 설정은 아래 정책을 따른다.

## Steam 로그인 토큰 교환

### `POST /auth/steam/token`

**Auth**

- None

**Request Body**

```json
{
  "loginCode": "temporary-login-code"
}
```

`loginCode`는 필수 `string`이며 Steam 콜백에서 기존 회원에게 발급한 1회용 코드다.

**Response 200**

```json
{
  "code": "200",
  "message": "로그인에 성공했습니다.",
  "responsedAt": "2026-09-14 17:00:00",
  "data": {
    "accessToken": "issued-access-token",
    "refreshToken": "issued-refresh-token"
  },
  "success": true
}
```

**Error Responses**

- `400`: 필드 검증 실패 (`VALIDATION_FAILED`), 잘못된 JSON·요청 본문 누락 (`INVALID_REQUEST`). 코드·메시지는 공통 오류 계약을 따른다.
- `401`: Login Code 무효·만료·이미 사용됨 (`STEAM_LOGIN_CODE_INVALID`, `Steam 로그인을 다시 진행해주세요.`)
- `500`: 서버 내부 오류 (`INTERNAL_SERVER_ERROR`, `서버 내부 오류가 발생했습니다.`)

```json
{
  "code": "STEAM_LOGIN_CODE_INVALID",
  "message": "Steam 로그인을 다시 진행해주세요.",
  "responsedAt": "2026-09-14 17:00:00"
}
```

**Processing Rules / Notes — 구현 메모**

1. `loginCode`의 존재 여부, 만료 여부, 이미 사용된 코드인지 확인한다.
2. 코드와 연결된 회원을 확인한다.
3. 정상인 경우 `loginCode`를 사용 완료 처리하고 Access Token과 Refresh Token을 발급한다.

- 정상 사용한 `loginCode`는 다시 사용할 수 없다. 동시에 교환 요청이 발생해도 한 번만 사용되도록 보장한다.
- Access Token과 Refresh Token은 Response Body의 `data`에 반환한다.

## Steam 회원가입 완료

### `POST /auth/steam/signup`

**Auth**

- None

**Request Body**

```json
{
  "signupToken": "temporary-signup-token",
  "nickname": "thispatch"
}
```

`signupToken`, `nickname`은 필수 `string`이다. 닉네임 상세 검증 규칙은 미정이다.
클라이언트는 `steamId`를 직접 전달하지 않는다. Steam ID는 `signupToken`에 연결된 서버 측 Steam 인증 결과에서 확인한다.

**Response 200**

```json
{
  "code": "200",
  "message": "회원가입에 성공했습니다.",
  "responsedAt": "2026-09-14 17:00:00",
  "data": {
    "accessToken": "issued-access-token",
    "refreshToken": "issued-refresh-token"
  },
  "success": true
}
```

**Error Responses**

- `400`: 필수 필드 누락·빈 값, 닉네임 검증 실패 (`VALIDATION_FAILED`, `입력값을 확인해주세요.`)
- `400`: 잘못된 JSON·요청 본문 누락 (`INVALID_REQUEST`, `올바르지 않은 요청입니다.`)
- `401`: Signup Token 무효·만료·이미 사용됨 또는 가입 용도 불일치 (`STEAM_SIGNUP_TOKEN_INVALID`, `Steam 인증을 다시 진행해주세요.`)
- `409`: 이미 가입된 Steam ID (`STEAM_ACCOUNT_ALREADY_REGISTERED`, `이미 가입된 Steam 계정입니다.`)
- `500`: 서버 내부 오류 (`INTERNAL_SERVER_ERROR`, `서버 내부 오류가 발생했습니다.`)

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해주세요.",
  "responsedAt": "2026-09-14 17:00:00",
  "errors": [
    {
      "field": "nickname",
      "message": "닉네임을 입력해주세요."
    }
  ]
}
```

```json
{
  "code": "STEAM_SIGNUP_TOKEN_INVALID",
  "message": "Steam 인증을 다시 진행해주세요.",
  "responsedAt": "2026-09-14 17:00:00"
}
```

**Processing Rules / Notes — 구현 메모**

1. `signupToken`의 존재 여부, 만료 여부, 회원가입 용도인지, 이미 사용된 토큰인지 확인한다.
2. 토큰과 연결된 Steam ID를 확인한다.
3. 닉네임을 검증한다.
4. 동일 Steam ID의 회원 존재 여부를 재확인한다.
5. 회원을 생성한다.
6. `signupToken`을 사용 완료 처리한다.
7. Access Token과 Refresh Token을 발급한다.

- Steam 가입 시 서버 내부 값:
  - `login_type = STEAM`
  - `steam_id = Steam 인증으로 검증된 Steam ID`
  - `nickname = 요청 nickname`
  - `status = ACTIVE`
  - `email = null`
  - `password = null`
- 회원 생성과 `signupToken` 사용 처리는 함께 성공하거나 함께 실패해야 한다.
- 닉네임 검증 실패 시 `signupToken`을 소모하지 않는다.
- 동일 Steam ID의 동시 가입 요청에도 중복 회원이 생성되지 않도록 서비스 검증과 DB UNIQUE 제약을 고려한다. 현재 migration에는 `member.steam_id` UNIQUE 제약이 없으며, 구체적인 추가 migration은 구현 전 확인 대상이다.
- 자체 가입 계정과 Steam 계정은 별도 회원으로 운영하며 연결·병합하지 않는다.

## 토큰 재발급

### `POST /auth/refresh`

**Auth**

- None

**Request Body**

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 200**

```json
{
  "code": "200",
  "message": "토큰 재발급에 성공했습니다.",
  "responsedAt": "2026-09-12 18:20:00",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
  },
  "success": true
}
```

**Error Responses**

- `400`: Refresh Token 누락
- `401`: Refresh Token 무효 또는 만료
- `500`: 서버 내부 오류

## 로그아웃

### `POST /auth/logout`

**Auth**

- Required
- `Authorization: Bearer {ACCESS_TOKEN}`

**Request Body**

```json
{
  "refreshToken": "issued-refresh-token"
}
```

`refreshToken`은 필수 `string`이며 현재 사용자에게 발급된 Refresh Token을 전달한다.

**Response 200**

```json
{
  "code": "200",
  "message": "로그아웃되었습니다.",
  "responsedAt": "2026-09-14 17:00:00",
  "data": null,
  "success": true
}
```

**Error Responses**

- `400`: 필드 검증 실패 (`VALIDATION_FAILED`), 잘못된 JSON·요청 본문 누락 (`INVALID_REQUEST`). 코드·메시지는 공통 오류 계약을 따른다.
- `401`: Access Token 인증 필요·무효·만료. Security에서 인증된 사용자가 없는 경우 `UNAUTHORIZED`, `인증이 필요합니다.`를 반환한다.
- `500`: 서버 내부 오류 (`INTERNAL_SERVER_ERROR`, `서버 내부 오류가 발생했습니다.`)
- Refresh Token 소유자 불일치·검증 불가 시 오류 상태 코드·메시지는 미정이다. 다른 사용자의 토큰을 무효화해서는 안 된다.

**Processing Rules / Notes — 구현 메모**

1. Authorization Bearer Access Token으로 현재 사용자를 식별한다.
2. 전달된 Refresh Token이 해당 사용자에게 발급된 토큰인지 확인한다.
3. 서버에 저장된 해당 Refresh Token을 삭제하거나 무효화한다.

- Access Token blacklist는 사용하지 않는다. 기존 Access Token은 만료 시점까지 유효하며 자연 만료된다.
- 유효한 Access Token과 소유자 확인을 전제로, 이미 해당 Refresh Token이 무효화된 경우에도 성공하도록 멱등하게 처리한다.
- 삭제·무효화 이후에도 동일 사용자의 재요청 여부를 확인할 수 있어야 한다. 구체적인 Refresh Token 저장·소유자 확인 방식은 구현 시 정한다.

## 회원탈퇴

### `DELETE /members/me`

**Auth**

- Required

**Request Body**: 없음

**Response 200**

```json
{
  "code": "200",
  "message": "회원탈퇴가 완료되었습니다.",
  "responsedAt": "2026-09-12 18:13:00",
  "success": true
}
```

**Error Responses**

- `401`: 인증 필요
- `500`: 서버 내부 오류

**Processing Rules / Notes — 구현 메모**

현재 DB 구조에서는 물리 삭제보다 `member.status`를 탈퇴 상태로 변경하는 soft withdrawal을 기본 방향으로 본다.

## Redirect URL 설정

Redirect 관련 URL은 환경변수로 관리한다.

```yaml
app:
  frontend-base-url: ${FRONTEND_BASE_URL}
  backend-public-url: ${BACKEND_PUBLIC_URL}
```

로컬 예:

```env
FRONTEND_BASE_URL=http://localhost:5173
BACKEND_PUBLIC_URL=http://localhost:8080
```

운영에서 외부 `/api` 경로가 백엔드로 연결되는 경우의 예:

```env
FRONTEND_BASE_URL=https://thispatch.com
BACKEND_PUBLIC_URL=https://thispatch.com/api
```

- Steam OpenID의 `return_to`는 `{BACKEND_PUBLIC_URL}/auth/steam/callback`을 사용하고 `realm`도 `BACKEND_PUBLIC_URL` 기준으로 설정한다.
- Docker 내부 주소나 Spring Boot 내부 주소를 Steam Redirect 주소로 사용하지 않는다.
- 프론트 복귀 주소는 `FRONTEND_BASE_URL`에 각 API의 경로를 조합한다.
- Access Token과 Refresh Token은 Redirect URL에 포함하지 않으며, 로그인 토큰 교환·회원가입 성공 시 Response Body로 반환한다.

## 미정 정책

- 닉네임 상세 검증 규칙: 미정.
- `loginCode`의 정확한 TTL: 미정. 짧은 수명의 1회용 코드로 사용한다.
- `signupToken`의 정확한 TTL: 미정. 짧은 수명의 가입 전용 1회용 토큰으로 사용한다.
- Refresh Token rotation 여부: 미정. 기존 `POST /auth/refresh` 요청·응답 계약은 유지한다.
- 탈퇴한 Steam 계정의 재가입 정책: 미정.
- 로그아웃의 Refresh Token 소유자 불일치·검증 불가 오류의 상태 코드·메시지: 미정.
