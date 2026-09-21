import { REVIEW_TOPICS } from "@/types/review"

interface TopicFilterBarProps {
  selectedTopicIds: number[]
  onToggleTopic: (topicId: number) => void
  onClear: () => void
  /** 현재 필터 결과 건수 */
  filteredCount: number | null
  /** 필터 없는 전체 건수 */
  totalCount: number | null
}

/** 토픽별 칩 강조색(선택 시 좌측 인디케이터). */
const TOPIC_ACCENT: Record<number, string> = {
  1: "bg-sb-pos",
  2: "bg-sb-mark",
  3: "bg-sb-primary-soft",
  4: "bg-sb-neg",
  5: "bg-sb-primary-deep",
}

/**
 * 리뷰 검색 토픽 다중 선택 바.
 * 여러 토픽은 OR로 목록 API에 전달된다.
 */
export function TopicFilterBar({
  selectedTopicIds,
  onToggleTopic,
  onClear,
  filteredCount,
  totalCount,
}: TopicFilterBarProps) {
  const hasSelection = selectedTopicIds.length > 0
  // countLabel: 「4건 / 전체 18건」 — 값이 없으면 자리만 유지하지 않고 안내
  const countLabel =
    filteredCount !== null && totalCount !== null
      ? `${filteredCount.toLocaleString("en-US")}건 / 전체 ${totalCount.toLocaleString("en-US")}건`
      : "건수를 불러오는 중…"

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="flex flex-wrap items-start justify-between gap-sb-3">
        <div>
          <h2 className="text-sb-title font-medium text-sb-ink">리뷰 검색</h2>
          <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
            최근 14일 리뷰 원문과 번역문으로 확인할 수 있습니다.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-sb-3">
          <p className="font-sb-mono text-sb-caption tabular-nums text-sb-ink-mute">{countLabel}</p>
          {hasSelection ? (
            <button
              type="button"
              onClick={onClear}
              className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-caption text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
            >
              × 선택 해제
            </button>
          ) : null}
        </div>
      </header>

      <ul className="mt-sb-4 flex flex-wrap gap-sb-2">
        {REVIEW_TOPICS.map((topic) => {
          const selected = selectedTopicIds.includes(topic.id)
          const accent = TOPIC_ACCENT[topic.id] ?? "bg-sb-primary"
          return (
            <li key={topic.id}>
              <button
                type="button"
                aria-pressed={selected}
                onClick={() => onToggleTopic(topic.id)}
                className={
                  selected
                    ? "flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-primary bg-sb-canvas-active px-sb-3 text-sb-body text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
                    : "flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline-cool bg-sb-canvas px-sb-3 text-sb-body text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
                }
              >
                <span className={`h-2.5 w-2.5 shrink-0 rounded-sm ${accent}`} aria-hidden />
                {topic.name}
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
