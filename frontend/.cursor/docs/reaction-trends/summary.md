# [S15P21A202-84] 반응 추세

## 이슈

| 항목   | 내용                                                              |
| ------ | ----------------------------------------------------------------- |
| 스토리 | [S15P21A202-84](https://ssafy.atlassian.net/browse/S15P21A202-84) |
| 하위   | -113 차트 · -115 스크롤·도넛 · -114 AI · -116 패치 모달           |
| 브랜치 | `S15P21A202-84-fe-반응-추세`                                      |

## 구현 요약

- **recharts**: `ComposedChart`(긍정률 Line + 채널 스택 Bar), `ReferenceLine` 패치, `Pie` 도넛
- **직접**: 메트릭 헤더, 패치 셀렉트, 가로 스크롤 셸, 뷰포트 구간 합계, AI 스크롤 idle 디바운스, 패치 모달
- API: `GET .../reaction-trends`, `.../summaries/reaction-trends`, `.../patches/{patchId}`
- 훅: `statisticsQueries` + `keepPreviousData`(과거일 확장 시)
- MSW: `reactionTrendsHandlers.ts`
- 진입 42일(`startDate` = 오늘−41, Seoul). 채널은 daily 필드 프론트 합산
- 왼쪽 스크롤 끝 → `startDate` 과거로 fetch. 스크롤 멈춤 후 AI 요약(COMPLETED/SKIPPED)

## 라우트

`/games/:gameId/reaction-trends` → `ReactionTrendsPage`

## 검증

- `pnpm lint` / `pnpm build`
