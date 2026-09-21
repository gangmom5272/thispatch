import {
  infiniteQueryOptions,
  keepPreviousData,
  queryOptions,
  useInfiniteQuery,
  useQuery,
} from "@tanstack/react-query"
import { getRepresentativeReviews, getReviews, getReviewTranslation } from "@/api/review"

export const reviewKeys = {
  all: ["reviews"] as const,
  representative: (gameId: number) => [...reviewKeys.all, "representative", gameId] as const,
  /** topicKey: 정렬된 id를 콤마로 이은 문자열(빈 문자열=전체). */
  lists: () => [...reviewKeys.all, "list"] as const,
  list: (gameId: number, topicKey: string) => [...reviewKeys.lists(), gameId, topicKey] as const,
  total: (gameId: number) => [...reviewKeys.all, "total", gameId] as const,
  translation: (reviewId: number) => [...reviewKeys.all, "translation", reviewId] as const,
}

/** topicIds를 쿼리 키 문자열로 정규화한다. */
function topicKeyOf(topicIds: number[]): string {
  return [...topicIds].sort((a, b) => a - b).join(",")
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

const DEFAULT_LIMIT = 10

/** 리뷰 목록 무한 스크롤 옵션. */
export const reviewsInfiniteOptions = (gameId: number, topicIds: number[]) =>
  infiniteQueryOptions({
    queryKey: reviewKeys.list(gameId, topicKeyOf(topicIds)),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      getReviews(
        {
          gameId,
          topicIds: topicIds.length > 0 ? topicIds : undefined,
          cursor: pageParam,
          limit: DEFAULT_LIMIT,
        },
        signal,
      ),
    getNextPageParam: (lastPage) =>
      lastPage.page.hasNext ? (lastPage.page.nextCursor ?? undefined) : undefined,
  })

/**
 * 토픽 필터가 반영된 리뷰 목록을 무한 스크롤로 구독한다.
 * @param topicIds 비어 있으면 전체 목록
 */
export function useReviewsInfinite(gameId: number | null, topicIds: number[]) {
  return useInfiniteQuery({
    ...reviewsInfiniteOptions(gameId ?? 0, topicIds),
    enabled: gameId !== null,
    placeholderData: keepPreviousData,
  })
}

/**
 * 필터 없이 전체 건수만 조회한다 (「N건 / 전체 M건」의 M).
 */
export function useReviewsTotalCount(gameId: number | null) {
  return useQuery({
    queryKey: reviewKeys.total(gameId ?? 0),
    queryFn: ({ signal }) => getReviews({ gameId: gameId ?? 0, limit: 1 }, signal),
    select: (data) => data.page.totalCount,
    enabled: gameId !== null,
    staleTime: 60_000,
  })
}

/** 리뷰 번역 쿼리 옵션. enabled가 false면 요청하지 않는다. */
export const reviewTranslationOptions = (reviewId: number) =>
  queryOptions({
    queryKey: reviewKeys.translation(reviewId),
    queryFn: ({ signal }) => getReviewTranslation(reviewId, signal),
    staleTime: Infinity,
  })

/**
 * 리뷰 번역을 구독한다. 「번역」 토글이 켜진 뒤에만 조회한다.
 * @param enabled false면 네트워크 요청을 하지 않는다
 */
export function useReviewTranslation(reviewId: number, enabled: boolean) {
  return useQuery({
    ...reviewTranslationOptions(reviewId),
    enabled,
  })
}
