import type { Review } from "@/types/review"

/**
 * 번역/원문 토글에 따라 표시할 본문을 고른다.
 * translatedBody가 없으면 원문(body)만 쓴다.
 */
export function resolveReviewBody(review: Review, showOriginal: boolean): string {
  if (showOriginal) return review.body
  return review.translatedBody ?? review.body
}
