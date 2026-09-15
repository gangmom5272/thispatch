# [S15P21A202-95] 게임 상세 헤더

## 이슈

| 항목          | 내용                                                                           |
| ------------- | ------------------------------------------------------------------------------ |
| 하위 작업     | [S15P21A202-95](https://ssafy.atlassian.net/browse/S15P21A202-95)              |
| 스토리        | [S15P21A202-79](https://ssafy.atlassian.net/browse/S15P21A202-79) 게임 상세 셸 |
| 형제 (미포함) | [S15P21A202-96](https://ssafy.atlassian.net/browse/S15P21A202-96) 탭 바        |
| 브랜치        | `S15P21A202-95-fe-게임-상세-헤더`                                              |

## 구현 요약

- `GET /games/{gameId}` → `GameDetail` 타입, `getGame`, `useGameDetail`
- MSW `GET /games/:gameId` (401/404, mock `lastCollectedAt`)
- `/games/:gameId`에 `GameDetailPage` + `GameHeader` (pen `SB C / GameHeader` 기준)
- 돌아가기 → `paths.games`, 수집 문구 `수집 MM-DD HH:mm · 수정일 기준 집계`
- 로딩 / 401 / 404 / 기타 오류 상태

## 제외

- 탭 바·탭 본문 (-96)
- 내 게임 토글 UI

## 검증

- `pnpm lint` — 통과
- `pnpm build` — 통과
