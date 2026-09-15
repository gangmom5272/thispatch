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
