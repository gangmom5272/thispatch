# 코드 스플리팅 개선 전 측정 (baseline)

- 측정일: 2026-09-29 / 기준 커밋: bfbe6ac
- 환경: `pnpm build` → `vite preview` (localhost:4173), 백엔드 없음(`/api/session` 실패 응답), Lighthouse 12.6.1, 각 3회 중앙값

## 번들

| 항목       | 값                                   |
| ---------- | ------------------------------------ |
| JS 파일 수 | 1개 (`index-BAZZif7o.js`)            |
| JS 용량    | 1,143.50 kB (gzip 339.94 kB)         |
| CSS        | 57.3 kB                              |
| 빌드 경고  | "Some chunks are larger than 500 kB" |

## 번들 구성 (소스맵 기준, 압축 후 바이트)

| 모듈                                                                               |    KB |  비율 |
| ---------------------------------------------------------------------------------- | ----: | ----: |
| recharts 계열 (recharts, d3-*, decimal.js-light, es-toolkit, redux toolkit, immer) | 362.5 | 31.8% |
| react-dom                                                                          | 174.4 | 15.7% |
| pages/GameDetail                                                                   |  98.7 |  8.9% |
| react-router                                                                       |  93.1 |  8.4% |
| pages/Landing                                                                      |  52.9 |  4.8% |
| axios                                                                              |  49.0 |  4.4% |

recharts 사용처: ReactionTrends 탭의 `ReactionTrendsChart.tsx`, `ChannelPanel.tsx` 2개 파일뿐.

## Lighthouse

| 지표             | 랜딩 데스크톱 | 로그인 데스크톱 | 랜딩 모바일 | 로그인 모바일 |
| ---------------- | ------------: | --------------: | ----------: | ------------: |
| Performance      |            96 |              98 |          62 |            69 |
| FCP              |         0.73s |           0.73s |       5.09s |         4.06s |
| LCP              |         1.29s |           1.01s |       6.77s |         5.56s |
| TBT              |          15ms |             0ms |       110ms |          24ms |
| Speed Index      |         0.87s |           0.73s |       5.58s |         4.72s |
| JS 전송량        |       329.3KB |         329.3KB |     329.3KB |       329.3KB |
| 사용되지 않는 JS |       227.3KB |         238.3KB |     227.3KB |       238.3KB |
| 메인 스레드 작업 |         610ms |           144ms |     1,366ms |         457ms |
