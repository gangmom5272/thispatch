import type { ReactionTrendsAiSummary } from "@/types/statistics"

interface AiSummaryCardProps {
  data: ReactionTrendsAiSummary | undefined
  isPending: boolean
  isError: boolean
  awaitingRange?: boolean
}

export function AiSummaryCard({
  data,
  isPending,
  isError,
  awaitingRange = false,
}: AiSummaryCardProps) {
  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      {awaitingRange && !isPending && !isError && !data ? (
        <p className="text-sb-body text-sb-ink-mute">구간 AI 요약을 준비하는 중…</p>
      ) : null}
      {isPending ? <p className="text-sb-body text-sb-ink-mute">구간 요약을 불러오는 중…</p> : null}
      {isError ? (
        <p className="text-sb-body text-sb-neg-text">요약을 불러오지 못했습니다.</p>
      ) : null}
      {!isPending && !isError && data?.summary.status === "SKIPPED" ? (
        <p className="text-sb-body text-sb-ink-mute">
          표본이 부족해 AI 요약을 건너뛰었습니다. 구간을 넓혀 보세요.
        </p>
      ) : null}
      {!isPending && !isError && data?.summary.status === "COMPLETED" && data.summary.text ? (
        <p className="text-sb-body leading-relaxed text-sb-ink">{data.summary.text}</p>
      ) : null}
      {!isPending && !isError && data?.summary.status === "COMPLETED" && !data.summary.text ? (
        <p className="text-sb-body text-sb-ink-mute">요약 본문이 비어 있습니다.</p>
      ) : null}
      {!awaitingRange && !isPending && !isError && !data ? (
        <p className="text-sb-body text-sb-ink-mute">스크롤이 멈추면 구간 AI 요약이 표시됩니다.</p>
      ) : null}
    </section>
  )
}
