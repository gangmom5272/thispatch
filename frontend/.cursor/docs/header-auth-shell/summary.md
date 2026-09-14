# 공통 헤더 · 로그인/회원가입 작업 요약

- **이슈**: [S15P21A202-88](https://ssafy.atlassian.net/browse/S15P21A202-88) (공통 헤더), 연관 [S15P21A202-76](https://ssafy.atlassian.net/browse/S15P21A202-76) (회원가입·로그인)
- **브랜치**: `S15P21A202-88-fe-공통-헤더`
- **일자**: 2026-09-14

## 목표

앱 공통 헤더(게스트/로그인 분기)와, 헤더 CTA가 연결되는 로그인·회원가입 페이지를 구현한다. 동작에 필요한 API 클라이언트·토큰·세션·라우트 골격을 함께 포함한다.

## 구현 내용

### API · 세션

- `src/api/client.ts`: envelope unwrap, Bearer 부착, 401 시 refresh single-flight (`POST /auth/refresh` body `{ refreshToken }`)
- `src/api/error.ts`, `src/api/session.ts`, `src/api/auth.ts`
- `src/lib/tokenStorage.ts`: access/refresh localStorage 저장·삭제 (로그아웃 = 토큰 삭제만)
- `useSession` / `useLogout`, `useLogin` / `useSignup`

### UI · 라우팅

- `AppShell` + `AppHeader`: 설계 원본 `.cursor/export/thispatch.pen`의 `SB C / Header`
- 로고(lucide `git-compare-arrows` 대응 SVG) · 게스트(로그인·회원가입) · 로그인(닉네임 칩·로그아웃)
- 라우트: `/`, `/games`, `/games/:gameId`, `/methodology`(placeholder), `/login`, `/signup`
- 로그인/가입 화면은 pen에 없음 → `sb-*` + 헤더/입력 패턴
- 페이지 조립 패턴: `*Page.tsx` + `pages/<page>/components/`
- Cursor 규칙: `.cursor/rules/design-from-pen.mdc` (이후 UI는 pen 기준)

### 기타

- Vite/TS `@/` 경로 별칭
- MSW: `/session`, `/auth/login`, `/auth/signup`, `/auth/refresh`
- Prettier `endOfLine: "auto"` (Windows CRLF lint)

## 검증

- `pnpm lint` 통과
- `pnpm build` 통과

## 로컬 확인용 MSW 계정

- email: `user@example.com`
- password: `password`

## 제외 범위

랜딩/방법론/게임 목록·상세 본문, Steam OAuth, 로그아웃 API는 포함하지 않음.
