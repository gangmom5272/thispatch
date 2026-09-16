import { api } from "@/api/client"
import type { Review, ReviewsListData, ReviewsListParams } from "@/types/review"

/**
 * 최근 대표 리뷰(최대 3건)를 조회한다.
 * GET /games/{gameId}/reviews/representative
 */
export function getRepresentativeReviews(gameId: number, signal?: AbortSignal): Promise<Review[]> {
  return api.get<Review[]>({
    path: `/games/${gameId}/reviews/representative`,
    config: { signal },
  })
}

/**
 * 리뷰 목록을 커서 페이지로 조회한다.
 * topicIds는 콤마 구분(게임 목록 genreIds와 동일). 서버·MSW는 OR로 해석한다.
 */
export function getReviews(
  { gameId, topicIds, cursor, limit }: ReviewsListParams,
  signal?: AbortSignal,
): Promise<ReviewsListData> {
  return api.get<ReviewsListData>({
    path: `/games/${gameId}/reviews`,
    config: {
      params: {
        topicIds: topicIds?.length ? topicIds.join(",") : undefined,
        cursor,
        limit,
      },
      signal,
    },
  })
}
