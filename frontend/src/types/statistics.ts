export interface StatPeriod {
  startDate: string
  endDate: string
  dayCount: number
}

export interface ReactionTrendsMeta {
  period: StatPeriod
  timezone: string
  aggregationBasis: "UPDATED_AT"
  lastCollectedAt: string | null
  dataStatus: "AVAILABLE" | "PARTIAL" | "EMPTY"
}

export interface ReactionTrendPatchMarker {
  id: string
  title: string
  patchedOn: string
  patchIndex: number
  totalPatchCount: number
}

export interface ReactionTrendDaily {
  date: string
  dataAvailable: boolean
  reviewCount: number
  positiveCount: number
  negativeCount: number
  positiveRate: number | null
  firstWrittenCount: number
  updatedCount: number
  firstWrittenPositiveCount: number
  firstWrittenNegativeCount: number
  updatedPositiveCount: number
  updatedNegativeCount: number
  patches: ReactionTrendPatchMarker[]
}

export interface ReactionTrendPeriodSummary {
  reviewCount: number
  positiveCount: number
  negativeCount: number
  positiveRate: number | null
  firstWrittenCount: number
  firstWrittenPositiveCount: number
  firstWrittenNegativeCount: number
  firstWrittenPositiveRate: number | null
  updatedCount: number
  updatedPositiveCount: number
  updatedNegativeCount: number
  updatedPositiveRate: number | null
}

export interface ReactionTrends {
  meta: ReactionTrendsMeta
  availablePeriod: StatPeriod
  summary: ReactionTrendPeriodSummary
  daily: ReactionTrendDaily[]
}

export type ReactionTrendsSummaryStatus = "COMPLETED" | "SKIPPED"

export interface ReactionTrendsAiSummary {
  meta: ReactionTrendsMeta
  summary: {
    status: ReactionTrendsSummaryStatus
    text: string | null
    targetPeriod: StatPeriod
    reasonCode: "INSUFFICIENT_SAMPLE" | null
  }
}

export interface PatchDetail {
  patchId: string
  gameId: number
  title: string
  patchedOn: string
  publishedAt: string
  body: string
  bodyFormat: "PLAIN_TEXT" | "HTML"
  url: string | null
}

/** 플레이타임 구간 식별자. ALL=전체, B1~B4=사분위 경계 구간. */
export type PlaytimeBandId = "ALL" | "B1" | "B2" | "B3" | "B4"

export interface PlaytimeTopicsMeta {
  period: StatPeriod
  timezone: string
  aggregationBasis: "UPDATED_AT"
  dataStatus: "AVAILABLE" | "PARTIAL" | "EMPTY"
}

export interface PlaytimeScale {
  /** 경계 산출에 쓴 표본 출처. 항상 게임 전체 리뷰. */
  source: "ALL_GAME_REVIEWS"
  sampleCount: number
  p25Minutes: number
  medianMinutes: number
  p75Minutes: number
}

export interface PlaytimeBandStats {
  band: PlaytimeBandId
  minMinutes: number
  /** null이면 상한 없음(B4). */
  maxMinutesExclusive: number | null
  reviewCount: number
  positiveCount: number
  negativeCount: number
  positiveRate: number | null
  sampleSufficient: boolean
}

export interface PlaytimeTopicHighestBand {
  band: Exclude<PlaytimeBandId, "ALL">
  mentionRate: number
}

export interface PlaytimeTopicRow {
  topicId: number
  name: string
  mentionCount: number
  /** 선택 구간(또는 전체) 언급률(%). */
  mentionRate: number
  /** 전체 구간 언급률(%). 흰 선 기준. */
  overallMentionRate: number
  /** 전체 대비 차이(percentage points). 전체 선택 시 null. */
  differencePp: number | null
  highestBand: PlaytimeTopicHighestBand | null
}

export interface PlaytimeFallbackReview {
  id: number
  sentiment: "POSITIVE" | "NEGATIVE"
  reviewDate: string
  playtimeMinutes: number
  languageCode: string
  /** 도움됨(추천) 수. 없으면 0. */
  helpfulCount?: number
  body: string
}

export interface PlaytimeFallbackBandItems {
  band: Exclude<PlaytimeBandId, "ALL">
  items: PlaytimeFallbackReview[]
}

export interface PlaytimeTopicsFallback {
  reasonCode: "INSUFFICIENT_SAMPLE"
  message: string
  totalCount: number
  itemsByBand: PlaytimeFallbackBandItems[]
}

export interface PlaytimeTopics {
  meta: PlaytimeTopicsMeta
  selectedBand: PlaytimeBandId
  minimumSampleCount: number
  sampleSufficient: boolean
  scale: PlaytimeScale
  overall: PlaytimeBandStats
  bands: PlaytimeBandStats[]
  topics: PlaytimeTopicRow[]
  fallback: PlaytimeTopicsFallback | null
}

export type PlaytimeTopicsSummaryStatus = "COMPLETED" | "SKIPPED"

/** AI 요약 근거로 노출하는 대표 리뷰 원문. */
export interface PlaytimeEvidenceReview {
  id: number
  sentiment: "POSITIVE" | "NEGATIVE"
  reviewDate: string
  playtimeMinutes: number
  languageCode: string
  /** 도움됨(추천) 수. API에 없으면 0. */
  helpfulCount: number
  body: string
}

export interface PlaytimeTopicsAiSummary {
  meta: PlaytimeTopicsMeta
  selectedBand: PlaytimeBandId
  summary: {
    status: PlaytimeTopicsSummaryStatus
    text: string | null
    recurringExpressions: string[]
    targetPeriod: StatPeriod
    targetReviewCount: number
    usedReviewCount: number | null
    selection: {
      code: string
      limit: number
      description: string
    } | null
    reasonCode: "INSUFFICIENT_SAMPLE" | null
  }
  /** 요약 카드 아래 대표 리뷰 2건. 없으면 빈 배열. */
  evidenceReviews: PlaytimeEvidenceReview[]
}
