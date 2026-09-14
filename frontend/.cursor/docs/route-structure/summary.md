# 라우트 구조 등록 작업 요약

- **이슈**: [S15P21A202-89](https://ssafy.atlassian.net/browse/S15P21A202-89) (라우트 구조 등록)
- **상위**: [S15P21A202-75](https://ssafy.atlassian.net/browse/S15P21A202-75) (앱 셸·라우팅·API 클라이언트)
- **브랜치**: `S15P21A202-89-fe-라우트-구조-등록`
- **일자**: 2026-09-14

## 배경

`-88` 공통 헤더·로그인/가입이 스토리 브랜치에 머지되면서 명세 path는 이미 [`src/router/index.tsx`](../../../src/router/index.tsx)에 등록된 상태였다. 이 이슈에서는 중복 UI 구현 없이 **경로 단일 출처**를 두고 검증·문서화했다.

## 등록 path

| 명세             | path 상수                                        | 화면                   |
| ---------------- | ------------------------------------------------ | ---------------------- |
| `/`              | `paths.home`                                     | `PlaceholderPage` (홈) |
| `/games`         | `paths.games`                                    | placeholder            |
| `/games/:gameId` | `paths.gameDetailPattern` / `gameDetailPath(id)` | placeholder            |
| `/methodology`   | `paths.methodology`                              | placeholder            |
| 로그인           | `paths.login`                                    | `LoginPage`            |
| 가입             | `paths.signup`                                   | `SignupPage`           |
| (추가)           | `*`                                              | `NotFound`             |

셸: `AppShell` + `AppHeader` + `Outlet` (-88)

## 경로 상수

- 위치: [`src/router/paths.ts`](../../../src/router/paths.ts)
- 사용처: 라우터 등록, `AppHeader` / `BrandLogo`, 로그인·가입 footer·성공 후 `navigate`, `NotFound` 홈 링크
- 중첩 라우트용 세그먼트: `routeSegment(absolutePath)`

## 검증

- `pnpm lint` 통과
- `pnpm build` 통과

## 제외

랜딩·게임 목록·방법론 본문, API 클라이언트 추가 작업은 해당 스토리/하위 이슈에서 진행한다.
