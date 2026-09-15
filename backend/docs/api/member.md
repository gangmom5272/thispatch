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

`user.id`는 `long`, `user.nickname`은 `string | null`이다.

**Response 200 - 로그인 상태, 닉네임 미설정**

```json
{
  "code": "200",
  "message": "성공했습니다.",
  "responsedAt": "2026-09-15 09:00:00",
  "data": {
    "authenticated": true,
    "user": {
      "id": 1,
      "nickname": null
    }
  },
  "success": true
}
```

- Steam 콜백에서 생성된 회원의 닉네임이 없어도 정상 로그인 상태다. `authenticated=true`와 회원 정보를 반환한다.
- 프론트는 `user.nickname == null`이면 닉네임 설정 화면으로 이동한다. 새로고침·재접속 시에도 같은 기준을 사용한다.
- 별도의 `onboardingRequired` 필드는 반환하지 않는다.

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

- `503`: DB 접근 또는 조회 트랜잭션 실패로 세션 정보 조회 불가 (`SESSION_UNAVAILABLE`, `세션 정보를 조회할 수 없습니다.`)
- `401`: Access Token 위조·형식 오류·용도 불일치, Bearer 형식 오류·중복 Authorization 헤더 (`UNAUTHORIZED`, `인증이 필요합니다.`)

> `/session`은 로그인 수행 API가 아니라 현재 로그인 상태 확인용이다.

- Authorization 헤더가 없거나 정상 Access Token이 만료된 경우에는 위의 비로그인 `200` 응답을 사용한다. 그 외 잘못된 토큰은 `401`이며, Refresh Token도 일반 인증에 사용할 수 없다.

- 유효한 Access Token이라도 회원이 없거나 `status != ACTIVE`이면 `200`, `authenticated=false`, `user=null`을 반환한다. 이 상태 판정은 `GET /session`에 적용한다.
- `ACTIVE` 회원은 닉네임이 `null`이어도 정상 로그인이다. 토큰 없음·만료 시에는 회원 DB를 조회하지 않는다. DB 조회 실패는 비로그인으로 바꾸지 않고 `503`으로 반환한다.

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

- `400`: 이메일·비밀번호 필드 검증 실패 (`VALIDATION_FAILED`, `입력값을 확인해주세요.`)
- `400`: 잘못된 JSON·요청 본문 누락·문자열이 아닌 필드 (`INVALID_REQUEST`, `올바르지 않은 요청입니다.`)
- `401`: 회원 없음·비밀번호 불일치·비활성 회원·Steam 계정 (`LOGIN_FAILED`, `이메일 또는 비밀번호가 일치하지 않습니다.`)
- `500`: 서버 내부 오류 (`INTERNAL_SERVER_ERROR`, `서버 내부 오류가 발생했습니다.`)

**Processing Rules / Notes**

1. `email`, `password`는 필수 `string`이다. 누락·null·빈 문자열·공백만인 값은 필드 검증 오류로 처리한다.
2. 이메일은 아래 공통 정규화 정책을 적용한 뒤 이메일 형식과 최대 255자를 검증하고, 정규화한 값으로 회원을 조회한다.
3. 비밀번호는 입력 원문 그대로 BCrypt로 검증한다. 앞뒤 공백 제거·대소문자 변환을 하지 않으며 UTF-8 기준 72바이트 이하여야 한다. 로그인에는 가입용 최소 길이·복잡도 규칙을 추가하지 않는다.
4. `login_type = LOCAL`이고 `status = ACTIVE`인 회원만 허용한다. 회원 없음·비밀번호 불일치·다른 가입 유형·비활성 상태는 구분 없이 동일한 `401 LOGIN_FAILED`를 반환한다.
5. 검증 성공 시 Access Token과 Refresh Token을 발급하고, 아래 Refresh Token 공통 저장 정책에 따라 기존 저장값을 대체한다. 저장까지 성공한 경우에만 Response Body의 `data.accessToken`, `data.refreshToken`으로 반환한다.
6. 토큰 저장은 로그인 서비스의 DB 트랜잭션에 참여한다. 조회·발급·저장 실패는 공통 `500`으로 처리하며, DB 트랜잭션 실패 시 기존 저장 토큰을 유지한다. 실패 응답에는 토큰·비밀번호·내부 예외 정보를 포함하지 않는다.

### 자체 회원 이메일 공통 정규화 정책

- 회원가입 저장·중복 검사·로그인 조회에 동일한 규칙을 적용한다.
- 입력 문자열의 앞뒤 공백을 제거(`String.strip()`)하고 `Locale.ROOT` 기준으로 전체를 소문자로 변환한다. 내부 공백은 제거하지 않는다.
- 회원가입은 정규화한 이메일을 저장하고 같은 값으로 중복 검사한다. 로그인도 정규화한 이메일로 조회한다.
- Gmail의 점 제거·`+` 별칭 제거 등 메일 제공자별 변환은 적용하지 않는다.
- 이번 로그인 구현에서는 기존 저장 이메일을 일괄 변환하거나 계정을 병합하지 않는다. 회원가입 구현도 이 공통 정책을 따라야 한다.

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
- OpenID 2.0 인증 요청은 `openid.mode=checkid_setup`과 `openid.claimed_id`·`openid.identity`의 `identifier_select` 값을 사용한다.
- 외부 경로 접두사(예: `/api`)는 유지하고 끝 슬래시를 정리해 콜백 경로를 붙인다. 요청 헤더·파라미터로 Redirect 주소를 덮어쓰지 않는다.
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

**Response 302 - 신규·기존 회원 공통**

```http
HTTP/1.1 302 Found
Location: {FRONTEND_BASE_URL}/auth/steam/callback?loginCode={LOGIN_CODE}
```

**Response 302 - Steam 인증 실패**

```http
HTTP/1.1 302 Found
Location: {FRONTEND_BASE_URL}/login?error=STEAM_AUTH_FAILED
```

**Processing Rules / Notes — 구현 메모**

1. Steam OpenID 응답을 백엔드에서 검증한다.
2. 검증된 `claimed_id`에서 Steam ID를 추출한다.
3. 해당 Steam ID로 `member`를 조회한다. 기존 회원이 없으면 닉네임 없이 회원을 생성한다.
4. 신규·기존 회원 모두 해당 회원 ID와 연결된 짧은 수명의 1회용 `loginCode`를 발급한다.
5. 위의 공통 프론트 콜백으로 리다이렉트한다. 프론트는 `POST /auth/steam/token`으로 코드를 교환한 뒤 응답의 `nickname`으로 화면을 분기한다.

- 신규 Steam 회원 생성 시 서버 내부 값:
  - `login_type = STEAM`
  - `steam_id = Steam 인증으로 검증된 Steam ID`
  - `nickname = null`
  - `status = ACTIVE`
  - `email = null`
  - `password = null`
- `loginCode`는 로그인 토큰 교환 전용이며 일반 API 인증에는 사용할 수 없다.
- Access Token과 Refresh Token은 Redirect URL에 포함하지 않는다.
- 회원 생성은 이 콜백에서 완료한다. 닉네임은 이후 인증된 회원이 `POST /auth/steam/signup`으로 설정한다.
- 닉네임 설정 화면에서 이탈해도 생성된 회원은 유지한다. 다음 Steam 로그인에서는 같은 Steam ID의 기존 회원을 사용하고, 닉네임이 여전히 `null`이면 설정 화면으로 돌아간다.
- 동일 Steam ID의 동시 콜백은 `member.steam_id` UNIQUE 제약과 충돌 시 기존 회원 조회로 중복 생성을 방지한다. V4 migration으로 일반 UNIQUE 제약을 추가하며 NULL은 여러 행에서 허용한다. 기존 중복 데이터는 자동 삭제·병합하지 않고 migration을 실패시킨다.
- 자체 가입 계정과 Steam 계정의 연결·병합은 지원하지 않는다.
- 기존 회원은 `login_type=STEAM`, `status=ACTIVE`인 경우에만 로그인 코드를 발급한다. 탈퇴 등 ACTIVE가 아닌 회원은 기존 회원과 데이터를 유지하고 코드 발급·자동 복구·새 회원 생성 없이 `/login?error=STEAM_AUTH_FAILED`로 302 Redirect한다. 탈퇴한 Steam 계정의 재가입은 허용하지 않는다.
- OpenID 2.0 필수 필드·서명 대상, Steam 공급자·식별자, 설정된 `return_to`를 확인한 뒤 고정된 Steam HTTPS endpoint에 `check_authentication`을 전송한다. 요청 값으로 외부 검증 주소를 바꾸지 않는다. 검증 성공 전에는 Steam ID로 회원을 조회하거나 생성하지 않는다.
- 인증 취소·누락·변조·무효 OpenID 응답 및 Steam 검증 서버의 타임아웃·HTTP 오류는 `/login?error=STEAM_AUTH_FAILED`로 302 Redirect한다. 실패 사유와 통신 장애는 서버 로그에서 구분하며 OpenID 서명·로그인 코드 원문은 기록하지 않는다.
- 내부 DB·Redis 장애는 공통 `500 INTERNAL_SERVER_ERROR` JSON 응답을 사용한다. 회원 생성 트랜잭션 커밋 후 로그인 코드를 발급하며, 코드 발급 실패 시에도 이미 생성된 회원은 유지한다. 사용자는 Steam 로그인을 다시 시작한다.
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

`loginCode`는 필수 `string`이며 Steam 콜백에서 신규·기존 회원에게 발급한 1회용 코드다.

**Response 200**

```json
{
  "code": "200",
  "message": "로그인에 성공했습니다.",
  "responsedAt": "2026-09-14 17:00:00",
  "data": {
    "accessToken": "issued-access-token",
    "refreshToken": "issued-refresh-token",
    "nickname": "thispatch"
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

1. `loginCode`의 존재 여부, 로그인 토큰 교환 용도, 만료 여부, 이미 사용된 코드인지 확인한다.
2. 코드를 원자적으로 소비해 얻은 회원 ID로 회원을 조회한다. `login_type = STEAM`이고 `status = ACTIVE`인 회원만 토큰 교환을 허용한다. 회원이 없거나 다른 가입 유형·상태이면 `401 STEAM_LOGIN_CODE_INVALID`와 `Steam 로그인을 다시 진행해주세요.`를 반환한다.
3. 회원 확인을 통과하면 Access Token과 Refresh Token을 발급한다. 발급한 Refresh Token은 아래 공통 저장 정책에 따라 저장한다.
4. 토큰과 함께 현재 회원의 닉네임을 `data.nickname`으로 반환한다.

- 정상 사용한 `loginCode`는 다시 사용할 수 없다. 동시에 교환 요청이 발생해도 한 번만 사용되도록 보장한다.
- Access Token과 Refresh Token은 Response Body의 `data`에 반환한다.
- `data.nickname`은 항상 포함하는 `string | null` 필드다. 닉네임 미설정 회원은 `null`이며, 신규·기존 여부와 관계없이 동일한 응답 구조를 사용한다.
- 프론트는 JWT를 전달받은 뒤 `data.nickname == null`이면 닉네임 설정 화면(`/signup/steam`)으로, 값이 있으면 메인 화면으로 이동한다.
- 별도의 `onboardingRequired` 필드는 반환하지 않는다.

## Steam 최초 닉네임 설정

### `POST /auth/steam/signup`

**Auth**

- Required
- `Authorization: Bearer {ACCESS_TOKEN}`

**Request Body**

```json
{
  "nickname": "thispatch"
}
```

Request Body는 필수 `string`인 `nickname`만 사용한다.

- 닉네임은 1~50자(Unicode 코드 포인트 기준)이며, 빈 문자열·공백으로만 이루어진 값은 허용하지 않는다.
- 문자 종류·다른 회원과의 중복은 제한하지 않는다. 다만 PostgreSQL 문자열에 저장할 수 없는 NUL(U+0000)은 검증 오류로 처리한다.
- 앞뒤 공백 제거나 대소문자 변환 없이 입력값 그대로 저장한다.
- 누락·null·공백만 있는 값·길이 초과·NUL은 `400 VALIDATION_FAILED`, 문자열이 아닌 JSON 값은 `400 INVALID_REQUEST`로 처리한다.

대상 회원은 검증된 Access Token의 회원 ID로 확인한다. 클라이언트는 회원 ID나 Steam ID를 지정하지 않는다.

**Response 200**

```json
{
  "code": "200",
  "message": "닉네임이 설정되었습니다.",
  "responsedAt": "2026-09-14 17:00:00",
  "data": {
    "nickname": "thispatch"
  },
  "success": true
}
```

**Error Responses**

- `400`: 필수 필드 누락·빈 값, 닉네임 검증 실패 (`VALIDATION_FAILED`, `입력값을 확인해주세요.`)
- `400`: 잘못된 JSON·요청 본문 누락 (`INVALID_REQUEST`, `올바르지 않은 요청입니다.`)
- `401`: Access Token 인증 필요·무효·만료 또는 회원 부재·`status != ACTIVE` (`UNAUTHORIZED`, `인증이 필요합니다.`). `WWW-Authenticate: Bearer` 헤더와 공통 오류 응답을 사용한다.
- `409`: 이미 닉네임이 설정됨 (`NICKNAME_ALREADY_SET`, `이미 닉네임이 설정된 회원입니다.`)
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
  "code": "NICKNAME_ALREADY_SET",
  "message": "이미 닉네임이 설정된 회원입니다.",
  "responsedAt": "2026-09-14 17:00:00"
}
```

**Processing Rules / Notes — 구현 메모**

1. Authorization Bearer Access Token으로 현재 회원을 식별한다.
2. 해당 회원이 존재하고 `status = ACTIVE`인지 확인한다. 로그인 유형(`LOCAL`/`STEAM`)은 제한하지 않는다. 회원 부재·비활성 상태는 이미 설정된 닉네임 여부보다 먼저 판정한다.
3. 닉네임을 검증한다.
4. 현재 닉네임이 `null`인 경우에만 최초 닉네임을 저장한다. 이미 값이 있으면 `409 NICKNAME_ALREADY_SET`을 반환한다.
5. 저장된 닉네임을 `data.nickname`에 필수 `string`으로 반환한다.

- 동일 회원의 동시 설정 요청에서도 최초 저장 한 번만 성공하고, 이후 요청은 `409`로 처리한다. 이미 설정된 닉네임을 덮어쓰지 않는다. 저장 시에도 `status = ACTIVE`와 `nickname IS NULL` 조건을 확인하고, 성공한 경우에만 `updated_at`을 갱신한다.
- 닉네임 검증 실패 시 회원의 기존 정보는 변경하지 않으며, 입력을 수정해 다시 요청할 수 있다.
- 이 API는 최초 닉네임 설정용이다. 회원 생성, Steam 인증, Access/Refresh Token 발급·저장은 수행하지 않는다.
- 성공 후 프론트는 메인 화면으로 이동한다. 이후 `/session`은 저장된 닉네임을 반환한다.

## 토큰 재발급

### Refresh Token 공통 저장 정책

- PostgreSQL의 `member.refresh_token_hash`, `member.refresh_token_expires_at`에 회원당 현재 Refresh Token 하나만 저장한다. 토큰 원문 대신 SHA-256 해시를 보관한다.
- 자체 로그인·회원가입 및 Steam 로그인 토큰 교환에서 발급된 토큰을 저장할 때 기존 값을 대체한다. 이전 Refresh Token은 더 이상 재발급에 사용할 수 없다. Steam 최초 닉네임 설정에서는 토큰을 발급하거나 교체하지 않는다.
- 동시 로그인 제한은 Refresh Token 기준이다. 기존 Access Token은 만료까지 유효하며 즉시 차단하지 않는다.
- Rotation은 사용하지 않는다. `POST /auth/refresh`는 현재 Refresh Token을 유지하고 기존 계약대로 새 Access Token만 반환한다.
- 재발급 검증은 기존 JWT 검증(서명·용도·필수 claim·만료) 후 JWT 회원 ID로 조회한 회원의 저장 해시와 만료 시각을 대조한다. 미저장·교체·폐기된 토큰은 사용할 수 없다.
- 폐기는 해당 회원의 현재 저장 해시가 전달된 토큰과 일치할 때 두 컬럼을 `NULL`로 바꾼다. 폐기 이력·기기 정보·token family는 보관하지 않으며 재사용 탐지에 따른 연관 토큰 폐기도 하지 않는다.
- 회원 상태는 조회할 수 있지만 상태별 인증 허용 여부는 후속 API 정책에서 결정한다.

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
3. 회원 ID와 토큰 해시가 모두 일치하는 현재 저장값만 제거한다.

- Access Token blacklist는 사용하지 않는다. 기존 Access Token은 만료 시점까지 유효하며 자연 만료된다.
- 유효한 Access Token과 소유자 확인을 전제로, 이미 해당 Refresh Token이 무효화된 경우에도 성공하도록 멱등하게 처리한다.
- 폐기 이력을 보관하지 않으므로 반복 요청의 소유자는 서명·용도·만료 검증을 통과한 JWT의 회원 ID와 현재 인증된 회원 ID를 비교해 확인한다.
- 위 검증을 통과하고 소유자가 같으면 저장값이 이미 없거나 다른 토큰으로 교체되었어도 성공한다. 과거 토큰으로 새 로그인 토큰을 폐기하지 않는다. 미저장 토큰과 폐기된 토큰의 이력은 구분하지 않는다.
- 만료된 Refresh Token은 기존 JWT 검증에서 `EXPIRED`로 구분된다. 이를 포함한 로그아웃 검증 실패의 HTTP 상태·코드·메시지는 위 미정 오류 정책을 따른다.

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
- Access Token과 Refresh Token은 Redirect URL에 포함하지 않으며, Steam 로그인 토큰 교환 성공 시 Response Body로 반환한다.

## Steam 로그인 코드 관리 정책

- 기본 TTL은 5분이며 `STEAM_LOGIN_CODE_TTL` 환경변수로 조정한다. 설정 경로는 `app.steam.login-code.ttl`이고 양의 정수 초 단위 Duration을 사용한다.
- `SecureRandom`으로 생성한 32바이트 난수를 패딩 없는 Base64 URL 형식(43자)으로 인코딩한다. JWT나 `signupToken`은 사용하지 않는다.
- Redis 키는 `thispatch:auth:steam:login-code:{SHA-256(loginCode)}`, 값은 회원 ID다. 코드 원문은 저장하거나 로그에 남기지 않는다.
- 발급 시 코드 해시·회원 ID·TTL을 `SET NX`로 함께 저장한다. 충돌하면 기존 코드와 TTL을 유지하고 새 난수로 재시도한다.
- 코드 관리 기반은 이미 저장된 회원 ID를 받는다. 회원 존재·상태 확인은 호출 API가 담당하며, 신규 회원 생성이 커밋된 후 코드를 발급해야 한다.
- 소비 없는 검증은 TTL을 연장하지 않는다. 검증 성공은 이후 소비를 예약하거나 보장하지 않는다.
- 토큰 교환 시 반드시 `GETDEL`로 원자적으로 소비해 얻은 회원 ID를 사용한다. 동시에 요청해도 한 건만 코드를 소비할 수 있다.
- 무효·만료·이미 사용된 코드는 `401 STEAM_LOGIN_CODE_INVALID`로 동일하게 처리한다. Redis 장애는 코드 무효로 바꾸지 않고 공통 `500 INTERNAL_SERVER_ERROR`로 처리한다.
- 소비 후 회원 확인·토큰 발급·저장에 실패하더라도 코드를 복구하지 않는다. DB 트랜잭션 롤백도 Redis 소비를 되돌리지 않으며 사용자는 Steam 로그인을 다시 시작한다. 소비 응답 유실로 결과가 불명확한 경우도 코드를 복구하지 않는다.
- PostgreSQL schema/migration 변경은 없다. Redis 재시작·데이터 유실로 코드가 사라지면 Steam 로그인을 다시 시작한다.
- 이 정책의 코드 관리 기반은 S15P21A202-135에서 제공하며, 콜백·토큰 교환 API 연결은 후속 이슈에서 구현한다.

## 미정 정책

- 로그아웃의 Refresh Token 소유자 불일치·검증 불가 오류의 상태 코드·메시지: 미정.
