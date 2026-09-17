import type { PlaytimeTopicRow } from "@/types/statistics"

interface TopicBarsProps {
  topics: PlaytimeTopicRow[]
  /** 선택 구간 리뷰 수 — "N건 중 M건" 표시용 */
  reviewCount: number
  /** true면 전체 선택 — differencePp/최고 구간 컬럼 표현이 달라짐 */
  isOverall: boolean
}

/**
 * 토픽별 언급률 가로 막대.
 * 빨간 막대=선택 구간 mentionRate, 흰 선=전체 overallMentionRate.
 * 토픽은 다중 라벨이라 합이 100%를 넘을 수 있다.
 */
export function TopicBars({ topics, reviewCount, isOverall }: TopicBarsProps) {
  // chartMax: 막대 스케일 상한(%). 합 100%로 정규화하지 않는다.
  const chartMax = Math.max(
    100,
    ...topics.flatMap((topic) => [topic.mentionRate, topic.overallMentionRate]),
  )

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-4">
        <h2 className="text-sb-title font-medium text-sb-ink">토픽별 언급률</h2>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
          토픽별 언급 리뷰 수 · 리뷰 하나에 여러 토픽이 포함될 수 있음 (합 100% 아님)
        </p>
        <ul className="mt-sb-2 flex flex-wrap gap-sb-4 text-sb-caption text-sb-ink-mute">
          <li className="flex items-center gap-sb-2">
            <span className="inline-block h-2.5 w-6 rounded-sb-tag bg-sb-neg" />
            현재 구간 언급률
          </li>
          <li className="flex items-center gap-sb-2">
            <span className="inline-block h-3 w-0.5 bg-sb-ink" />
            전체 구간 언급률
          </li>
        </ul>
      </header>

      {topics.length === 0 ? (
        <p className="text-sb-body text-sb-ink-mute">표시할 토픽이 없습니다.</p>
      ) : (
        <ul className="flex flex-col gap-sb-4">
          {topics.map((topic) => (
            <TopicBarRow
              key={topic.topicId}
              topic={topic}
              reviewCount={reviewCount}
              chartMax={chartMax}
              isOverall={isOverall}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

interface TopicBarRowProps {
  topic: PlaytimeTopicRow
  reviewCount: number
  chartMax: number
  isOverall: boolean
}

/** 단일 토픽 행: 라벨·비중·막대·전체 대비. */
function TopicBarRow({ topic, reviewCount, chartMax, isOverall }: TopicBarRowProps) {
  // barPct / linePct: 차트 너비 대비 위치(%)
  const barPct = Math.min(100, (topic.mentionRate / chartMax) * 100)
  const linePct = Math.min(100, (topic.overallMentionRate / chartMax) * 100)
  const diff = topic.differencePp

  return (
    <li className="grid gap-sb-2 md:grid-cols-[minmax(0,1.2fr)_minmax(0,2fr)_auto] md:items-center">
      <div>
        <p className="text-sb-body text-sb-ink">{topic.name}</p>
        <p className="font-sb-mono text-sb-caption text-sb-ink-mute">
          {reviewCount.toLocaleString("en-US")}건 중 {topic.mentionCount.toLocaleString("en-US")}건
        </p>
      </div>

      <div className="relative h-8 overflow-hidden rounded-sb-tag bg-sb-canvas">
        <div
          className="absolute inset-y-1 left-0 rounded-sb-tag bg-sb-neg"
          style={{ width: `${barPct}%` }}
          title={`현재 ${topic.mentionRate.toFixed(1)}%`}
        />
        <div
          className="absolute inset-y-0 w-0.5 bg-sb-ink"
          style={{ left: `calc(${linePct}% - 1px)` }}
          title={`전체 ${topic.overallMentionRate.toFixed(1)}%`}
        />
      </div>

      <div className="min-w-28 text-right font-sb-mono text-sb-caption tabular-nums">
        <p className="text-sb-ink">{topic.mentionRate.toFixed(1)}%</p>
        {isOverall ? (
          <p className="text-sb-ink-mute">
            최고 {topic.highestBand?.band ?? "—"}
            {topic.highestBand ? ` ${topic.highestBand.mentionRate.toFixed(1)}%` : ""}
          </p>
        ) : (
          <p
            className={
              diff === null
                ? "text-sb-ink-mute"
                : diff > 0
                  ? "text-sb-neg-text"
                  : diff < 0
                    ? "text-sb-pos-text"
                    : "text-sb-ink-mute"
            }
          >
            {diff === null ? "—" : `${diff > 0 ? "+" : ""}${diff.toFixed(1)}%p`}
          </p>
        )}
      </div>
    </li>
  )
}
