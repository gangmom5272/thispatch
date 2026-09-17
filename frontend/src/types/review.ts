import type { StatPeriod } from "@/types/statistics"

/** 리뷰 긍정/부정. */
export type ReviewSentiment = "POSITIVE" | "NEGATIVE"

/** 리뷰에 붙은 토픽 태그. */
export interface ReviewTag {
  id: number
  name: string
}

/** 대표·목록 공통 리뷰 아이템. */
export interface Review {
  id: number
  sentiment: ReviewSentiment
  isUpdated: boolean
  playtimeMinutes: number
  languageCode: string
  helpfulCount: number
  tags: ReviewTag[]
  /** 원문 본문 */
  body: string
  /**
   * 한국어 번역 본문. 없으면 null.
   * 번역 API는 없고 응답에 필드가 올 때만 토글로 표시한다.
   */
  translatedBody: string | null
  reviewDate: string
}

/** GET /games/{gameId}/reviews 응답 data. */
export interface ReviewsListData {
  meta: {
    period: StatPeriod
    timezone: string
    aggregationBasis: "UPDATED_AT"
    lastCollectedAt: string | null
  }
  items: Review[]
  page: {
    limit: number
    nextCursor: string | null
    hasNext: boolean
    totalCount: number
  }
}

/** 리뷰 목록 조회 인자. */
export interface ReviewsListParams {
  gameId: number
  /** 다중 선택 시 OR. 비우면 전체. */
  topicIds?: number[]
  cursor?: string
  limit?: number
}

/**
 * 리뷰 검색 칩용 토픽 카탈로그 (pen SB / 02c 5종).
 * 별도 topics API가 없어 FE 상수로 둔다.
 */
export const REVIEW_TOPICS = [
  { id: 1, name: "밸런스/너프·버프" },
  { id: 2, name: "최적화/버그/크래시" },
  { id: 3, name: "시스템/UI·편의성" },
  { id: 4, name: "외부/운영 이슈" },
  { id: 5, name: "과금/재화(BM)" },
] as const
