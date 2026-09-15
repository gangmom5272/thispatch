import type { ReactionTrendsAiSummary } from "@/types/statistics"

interface AiSummaryCardProps {
  data: ReactionTrendsAiSummary | undefined
  isPending: boolean
  isError: boolean
}

export function AiSummaryCard({ data, isPending, isError }: AiSummaryCardProps) {
  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <h2 className="text-sb-title font-medium text-sb-ink">관측 종합</h2>
      {isPending ? (
        <p className="mt-sb-3 text-sb-body text-sb-ink-mute">구간 요약을 불러오는 중…</p>
      ) : null}
      {isError ? (
        <p className="mt-sb-3 text-sb-body text-sb-neg-text">요약을 불러오지 못했습니다.</p>
      ) : null}
      {!isPending && !isError && data?.summary.status === "SKIPPED" ? (
        <p className="mt-sb-3 text-sb-body text-sb-ink-mute">
          표본이 부족해 AI 요약을 건너뛰었습니다. 구간을 넓혀 보세요.
        </p>
      ) : null}
      {!isPending && !isError && data?.summary.status === "COMPLETED" && data.summary.text ? (
        <p className="mt-sb-3 text-sb-body leading-relaxed text-sb-ink">{data.summary.text}</p>
      ) : null}
    </section>
  )
}
