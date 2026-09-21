import type { PlaytimeTopicRow } from "@/types/statistics"

interface TopicBarsProps {
  topics: PlaytimeTopicRow[]
  /** 선택 구간 리뷰 수 — "N건 중 M건" 표시용 */
  reviewCount: number
  /** true면 전체 선택 — differencePp/최고 구간 컬럼 표현이 달라짐 */
  isOverall: boolean
  /** true면 행 간격과 막대 높이를 줄여 고정 높이 화면(랜딩)에 맞춘다 */
  dense?: boolean
}

/**
 * 토픽별 언급률 가로 막대.
 * 빨간 막대=선택 구간 mentionRate, 흰 선=전체 overallMentionRate.
 * 토픽은 다중 라벨이라 합이 100%를 넘을 수 있다.
 */
export function TopicBars({ topics, reviewCount, isOverall, dense = false }: TopicBarsProps) {
  // chartMax: 막대 스케일 상한(%). 합 100%로 정규화하지 않는다.
  const chartMax = Math.max(
    100,
    ...topics.flatMap((topic) => [topic.mentionRate, topic.overallMentionRate]),
  )

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-4">
        <h2 className="text-sb-title font-medium text-sb-ink">리뷰 중 토픽 언급률</h2>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
          리뷰 하나에 여러 토픽이 포함될 수 있습니다. 비율 합계가 100%를 초과할 수 있습니다.
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
        <ul className={dense ? "flex flex-col gap-sb-2" : "flex flex-col gap-sb-4"}>
          {topics.map((topic) => (
            <TopicBarRow
              key={topic.topicId}
              topic={topic}
              reviewCount={reviewCount}
              chartMax={chartMax}
              isOverall={isOverall}
              dense={dense}
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
  dense: boolean
}

/** 단일 토픽 행: 라벨·비중·막대·전체 대비. dense면 라벨과 수치를 한 줄로 접는다. */
function TopicBarRow({ topic, reviewCount, chartMax, isOverall, dense }: TopicBarRowProps) {
  // barPct / linePct: 차트 너비 대비 위치(%)
  const barPct = Math.min(100, (topic.mentionRate / chartMax) * 100)
  const linePct = Math.min(100, (topic.overallMentionRate / chartMax) * 100)
  const diff = topic.differencePp

  return (
    <li className="grid gap-sb-2 md:grid-cols-[minmax(0,1.2fr)_minmax(0,2fr)_auto] md:items-center">
      <div className={dense ? "flex flex-wrap items-baseline gap-x-sb-2" : undefined}>
        <p className="text-sb-body text-sb-ink">{topic.name}</p>
        <p className="font-sb-mono text-sb-caption text-sb-ink-mute">
          {reviewCount.toLocaleString("en-US")}건 중 {topic.mentionCount.toLocaleString("en-US")}건
        </p>
      </div>

      <div
        className={
          dense
            ? "relative h-6 overflow-hidden rounded-sb-tag bg-sb-canvas"
            : "relative h-8 overflow-hidden rounded-sb-tag bg-sb-canvas"
        }
      >
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

      <div
        className={
          dense
            ? "flex min-w-28 justify-end gap-x-sb-2 text-right font-sb-mono text-sb-caption tabular-nums"
            : "min-w-28 text-right font-sb-mono text-sb-caption tabular-nums"
        }
      >
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
