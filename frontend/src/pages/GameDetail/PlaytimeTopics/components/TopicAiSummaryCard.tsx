import type { PlaytimeTopicsAiSummary } from "@/types/statistics"

interface TopicAiSummaryCardProps {
  data: PlaytimeTopicsAiSummary | undefined
  /** 실제 네트워크 로딩 중일 때만 true (disabled 쿼리 isPending 금지) */
  isPending: boolean
  isError: boolean
}

/**
 * 플레이타임×토픽 AI 요약 카드.
 * COMPLETED / SKIPPED / 로딩 / 에러 / 빈 본문을 분기한다.
 */
export function TopicAiSummaryCard({ data, isPending, isError }: TopicAiSummaryCardProps) {
  const used = data?.summary.usedReviewCount
  const total = data?.summary.targetReviewCount

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-3">
        <h2 className="text-sb-title font-medium text-sb-ink">AI 대표 반응 요약</h2>
        {used != null && total != null ? (
          <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
            선택 구간 {total.toLocaleString("en-US")}건 중 도움됨 상위{" "}
            {used.toLocaleString("en-US")}
            건을 바탕으로 요약
          </p>
        ) : null}
      </header>

      {isPending ? <p className="text-sb-body text-sb-ink-mute">요약을 불러오는 중…</p> : null}
      {isError ? (
        <p className="text-sb-body text-sb-neg-text">요약을 불러오지 못했습니다.</p>
      ) : null}

      {!isPending && !isError && data?.summary.status === "SKIPPED" ? (
        <p className="text-sb-body text-sb-ink-mute">
          표본이 부족해 AI 요약을 건너뛰었습니다. 다른 구간을 선택해 보세요.
        </p>
      ) : null}

      {!isPending && !isError && data?.summary.status === "COMPLETED" && data.summary.text ? (
        <>
          <p className="text-sb-body leading-relaxed text-sb-ink">{data.summary.text}</p>
          {data.summary.recurringExpressions.length > 0 ? (
            <div className="mt-sb-3">
              <p className="mb-sb-2 text-sb-caption text-sb-ink-mute">반복적으로 관찰된 표현</p>
              <ul className="flex flex-wrap gap-sb-2">
                {data.summary.recurringExpressions.map((expr) => (
                  <li
                    key={expr}
                    className="rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas px-sb-2 py-0.5 text-sb-caption text-sb-ink-mute"
                  >
                    {expr}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      ) : null}

      {!isPending && !isError && data?.summary.status === "COMPLETED" && !data.summary.text ? (
        <p className="text-sb-body text-sb-ink-mute">요약 본문이 비어 있습니다.</p>
      ) : null}
    </section>
  )
}
