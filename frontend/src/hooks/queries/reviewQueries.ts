import { queryOptions, useQuery } from "@tanstack/react-query"
import { getRepresentativeReviews } from "@/api/review"

export const reviewKeys = {
  all: ["reviews"] as const,
  representative: (gameId: number) => [...reviewKeys.all, "representative", gameId] as const,
}

/** 최근 대표 리뷰 쿼리 옵션. */
export const representativeReviewsOptions = (gameId: number) =>
  queryOptions({
    queryKey: reviewKeys.representative(gameId),
    queryFn: ({ signal }) => getRepresentativeReviews(gameId, signal),
  })

/** 게임별 최근 대표 리뷰를 구독한다. */
export function useRepresentativeReviews(gameId: number | null) {
  return useQuery({
    ...representativeReviewsOptions(gameId ?? 0),
    enabled: gameId !== null,
  })
}
