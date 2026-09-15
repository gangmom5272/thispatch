# 플레이타임 × 토픽 (S15P21A202-85)

## 범위

에픽 S15P21A202-74 하위 스토리. 하위 작업:

| 이슈           | 내용                                  |
| -------------- | ------------------------------------- |
| S15P21A202-117 | API·MSW·라우트 + 플레이타임 밴드 카드 |
| S15P21A202-118 | 토픽 가로 막대 + 전체 언급률 흰 선    |
| S15P21A202-119 | 표본 &lt; 30 시 리뷰 원문 fallback    |
| S15P21A202-120 | `summaries/playtime-topics` AI 요약   |

## 구현 위치

- 페이지: `src/pages/GameDetail/PlaytimeTopics/`
- API: `getPlaytimeTopics`, `getPlaytimeTopicsSummary` (`src/api/statistics.ts`)
- 훅: `usePlaytimeTopics`, `usePlaytimeTopicsSummary`
- MSW: `src/mocks/handlers/playtimeTopicsHandlers.ts`
- 라우트: `GAME_DETAIL_TABS.playtimeTopics` → `PlaytimeTopicsPage`

## 동작 요약

- `bandNo` null = 전체 보기, 1~~4 = B1~~B4
- 구간 경계는 응답 `scale`(게임 전체 리뷰 p25/중앙/p75)
- 토픽은 다중 라벨 → 합 100% 가정하지 않음
- `sampleSufficient === false`면 토픽·AI 숨기고 `fallback` 원문
- AI는 표본 충분할 때만 요청 (`isFetching && !data`로 로딩 표시)
- MSW: gameId `1517290` + B4 선택 시 표본 부족 시나리오

## 설계

pen `SB / 02`, `SB / 02b`, `SB / 02e` + `backend/docs/api/statistics.md`
