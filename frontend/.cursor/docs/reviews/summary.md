# 리뷰 (S15P21A202-86)

## 범위

에픽 S15P21A202-74 하위 스토리. 하위 작업:

| 이슈           | 내용                                |
| -------------- | ----------------------------------- |
| S15P21A202-121 | 최근 대표 리뷰 + representative API |
| S15P21A202-122 | 리뷰 목록·토픽 다중 선택 OR 필터    |

## 구현 위치

- 페이지: `src/pages/GameDetail/Reviews/`
- API: `getRepresentativeReviews`, `getReviews` (`src/api/review.ts`)
- 훅: `useRepresentativeReviews`, `useReviewsInfinite`, `useReviewsTotalCount`
- 타입: `src/types/review.ts` (`REVIEW_TOPICS` 5종 상수)
- MSW: `src/mocks/handlers/reviewHandlers.ts`
- 라우트: `GAME_DETAIL_TABS.reviews` → `ReviewsPage`

## 동작 요약

- 대표 리뷰: 최대 3건 그리드. 번역/원문 토글은 UI만(번역 필드 없음 → 항상 `body`)
- 토픽 칩: pen 5종 FE 상수. 다중 선택 시 OR (`topicIds` 콤마 직렬화)
- 칩 건수 API 없음 → 칩은 이름만. 상태 문구는 `filtered totalCount` / 무필터 `totalCount`
- 목록: `useInfiniteQuery` + 「더 보기」(limit 10)
- 고지: 토픽은 작성자 의도가 아니며 다중 선택은 OR

## 설계

pen `SB / 02c 리뷰` + `backend/docs/api/review.md`
