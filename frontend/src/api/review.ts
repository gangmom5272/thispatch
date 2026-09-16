import { api } from "@/api/client"
import type { Review } from "@/types/review"

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
