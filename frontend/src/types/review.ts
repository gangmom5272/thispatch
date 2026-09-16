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
  body: string
  reviewDate: string
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
