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

> `/session`은 로그인 수행 API가 아니라 현재 로그인 상태 확인용이다.

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
