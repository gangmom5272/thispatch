# [S15P21A202-84] 반응 추세

## 이슈

| 항목   | 내용                                                              |
| ------ | ----------------------------------------------------------------- |
| 스토리 | [S15P21A202-84](https://ssafy.atlassian.net/browse/S15P21A202-84) |
| 하위   | -113 차트 · -115 스크롤·도넛 · -114 AI · -116 패치 노트           |
| 브랜치 | `S15P21A202-84-fe-반응-추세`                                      |

## 구현 요약

- **recharts**: `ComposedChart`(긍정률 Line + 채널 스택 Bar), `ReferenceLine` 패치, `Pie` 도넛
- **직접**: 메트릭 헤더, 패치 셀렉트(선택 즉시 스크롤), 가로 스크롤 셸, 고정 Y축·범례, 「최근 날짜로」, 뷰포트 구간 합계, AI idle 디바운스, 인라인 패치 노트
- 한 화면 약 14일(`dayWidth = viewport / 14`)
- API: `GET .../reaction-trends`, `.../summaries/reaction-trends`, `.../patches/{patchId}`
- MSW: `reactionTrendsHandlers.ts`
- 진입 42일. 파이 수정 채널은 붉은색, 범례에 건수 표시

## 라우트

`/games/:gameId/reaction-trends` → `ReactionTrendsPage`

## 검증

- `pnpm lint` / `pnpm build`
