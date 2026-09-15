# [S15P21A202-96] 게임 상세 탭 바

## 이슈

| 항목      | 내용                                                                           |
| --------- | ------------------------------------------------------------------------------ |
| 하위 작업 | [S15P21A202-96](https://ssafy.atlassian.net/browse/S15P21A202-96)              |
| 스토리    | [S15P21A202-79](https://ssafy.atlassian.net/browse/S15P21A202-79) 게임 상세 셸 |
| 형제      | [S15P21A202-95](https://ssafy.atlassian.net/browse/S15P21A202-95) 헤더         |
| 브랜치    | `S15P21A202-96-fe-게임-상세-탭-바`                                             |

## 구현 요약

- 중첩 라우트: `/games/:gameId/:tab` + index → `reaction-trends` redirect
- 탭: 반응 추세 / 플레이타임 · 토픽 / 리뷰 / 언어별 분석 / 기획안 입력
- `GameDetailTabs` (`NavLink`, 선택 시 민트 2px 하단선) + `TabPlaceholder` 본문
- `paths.ts`: `GAME_DETAIL_TABS`, `gameDetailTabPath`, `gameDetailPath` → 기본 탭 URL

## 제외

- 각 탭 실제 본문·API

## 검증

- `pnpm lint` — 통과
- `pnpm build` — 통과
