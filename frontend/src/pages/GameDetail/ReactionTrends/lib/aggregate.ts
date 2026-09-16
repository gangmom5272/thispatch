import type { ReactionTrendDaily, ReactionTrendPeriodSummary } from "@/types/statistics"
import { formatShortMd } from "@/lib/seoulDate"

export interface ChartRow {
  date: string
  label: string
  positiveRate: number | null
  firstWrittenCount: number
  updatedCount: number
  reviewCount: number
  patches: ReactionTrendDaily["patches"]
}

export function toChartRows(daily: ReactionTrendDaily[]): ChartRow[] {
  return daily.map((day) => ({
    date: day.date,
    label: formatShortMd(day.date),
    positiveRate: day.positiveRate,
    firstWrittenCount: day.firstWrittenCount,
    updatedCount: day.updatedCount,
    reviewCount: day.reviewCount,
    patches: day.patches,
  }))
}

export function sumDailyRange(days: ReactionTrendDaily[]): ReactionTrendPeriodSummary {
  let reviewCount = 0
  let positiveCount = 0
  let negativeCount = 0
  let firstWrittenCount = 0
  let firstWrittenPositiveCount = 0
  let firstWrittenNegativeCount = 0
  let updatedCount = 0
  let updatedPositiveCount = 0
  let updatedNegativeCount = 0

  for (const day of days) {
    if (!day.dataAvailable) continue
    reviewCount += day.reviewCount
    positiveCount += day.positiveCount
    negativeCount += day.negativeCount
    firstWrittenCount += day.firstWrittenCount
    firstWrittenPositiveCount += day.firstWrittenPositiveCount
    firstWrittenNegativeCount += day.firstWrittenNegativeCount
    updatedCount += day.updatedCount
    updatedPositiveCount += day.updatedPositiveCount
    updatedNegativeCount += day.updatedNegativeCount
  }

  const rate = (pos: number, total: number) => (total === 0 ? null : (pos / total) * 100)

  return {
    reviewCount,
    positiveCount,
    negativeCount,
    positiveRate: rate(positiveCount, reviewCount),
    firstWrittenCount,
    firstWrittenPositiveCount,
    firstWrittenNegativeCount,
    firstWrittenPositiveRate: rate(firstWrittenPositiveCount, firstWrittenCount),
    updatedCount,
    updatedPositiveCount,
    updatedNegativeCount,
    updatedPositiveRate: rate(updatedPositiveCount, updatedCount),
  }
}

export function formatRate(rate: number | null, digits = 1): string {
  if (rate === null) return "—"
  return rate.toFixed(digits)
}

export function formatCount(n: number): string {
  return n.toLocaleString("en-US")
}
