import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts"
import type { ReactionTrendPeriodSummary } from "@/types/statistics"
import { formatCount, formatRate } from "@/pages/GameDetail/ReactionTrends/lib/aggregate"

const COLORS = {
  firstPos: "#3ecf8e",
  firstNeg: "#1f5540",
  updatedPos: "#ff6b5e",
  updatedNeg: "#8a3a34",
}

const pieShellClass =
  "outline-none focus:outline-none focus-visible:outline-none [&_svg]:outline-none [&_svg:focus]:outline-none [&_*:focus]:outline-none"

interface ChannelPanelProps {
  summary: ReactionTrendPeriodSummary
  dayCount: number
}

export function ChannelPanel({ summary, dayCount }: ChannelPanelProps) {
  const data = [
    {
      name: "첫 작성 긍정",
      value: summary.firstWrittenPositiveCount,
      color: COLORS.firstPos,
    },
    {
      name: "첫 작성 부정",
      value: summary.firstWrittenNegativeCount,
      color: COLORS.firstNeg,
    },
    {
      name: "수정 긍정",
      value: summary.updatedPositiveCount,
      color: COLORS.updatedPos,
    },
    {
      name: "수정 부정",
      value: summary.updatedNegativeCount,
      color: COLORS.updatedNeg,
    },
  ].filter((item) => item.value > 0)

  return (
    <section className="flex flex-col gap-sb-4 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="flex flex-wrap items-baseline justify-between gap-sb-2">
        <h2 className="text-sb-title font-medium text-sb-ink">최초 작성 / 수정 리뷰 분포</h2>
        <p className="text-sb-caption text-sb-ink-mute">선택 구간 {dayCount}일</p>
      </header>
      <p className="rounded-sb-tag border border-sb-line-amber bg-sb-tint-amber px-sb-3 py-sb-2 text-sb-caption text-sb-amber-text">
        최초/수정 리뷰의 긍정/부정 반응 분포를 표현합니다. 첫 작성을 신규 유저로 해석하지 않습니다.
      </p>
      <div
        className={`h-64 w-full ${pieShellClass}`}
        tabIndex={-1}
        onMouseDown={(event) => {
          const target = event.target
          if (target instanceof Element && target.closest("svg")) {
            event.preventDefault()
          }
        }}
      >
        <ResponsiveContainer width="100%" height="100%">
          <PieChart style={{ outline: "none" }}>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              innerRadius={70}
              outerRadius={110}
              paddingAngle={2}
              isAnimationActive={false}
              activeShape={false}
            >
              {data.map((entry) => (
                <Cell key={entry.name} fill={entry.color} stroke="none" />
              ))}
            </Pie>
            <Tooltip
              wrapperStyle={{ outline: "none", pointerEvents: "none" }}
              contentStyle={{
                background: "#1b2838",
                border: "1px solid #2a475e",
                borderRadius: 8,
                outline: "none",
              }}
              formatter={(value) => `${formatCount(Number(value))}건`}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="flex flex-col gap-sb-2">
        {data.map((entry) => (
          <li
            key={entry.name}
            className="flex items-center justify-between gap-sb-3 text-sb-body text-sb-ink"
          >
            <span className="flex items-center gap-sb-2">
              <span
                className="inline-block size-3 shrink-0 rounded-sb-tag"
                style={{ background: entry.color }}
              />
              {entry.name}
            </span>
            <span className="font-sb-mono tabular-nums text-sb-ink-mute">
              {formatCount(entry.value)}건
            </span>
          </li>
        ))}
      </ul>
      <div className="grid gap-sb-3 sm:grid-cols-2">
        <div className="rounded-sb-tag border border-sb-hairline bg-sb-canvas p-sb-3">
          <p className="text-sb-caption text-sb-ink-mute">최초 리뷰 긍정 비율</p>
          <p className="mt-sb-1 font-sb-mono text-sb-title tabular-nums text-sb-pos-text">
            {formatRate(summary.firstWrittenPositiveRate)}%
          </p>
        </div>
        <div className="rounded-sb-tag border border-sb-hairline bg-sb-canvas p-sb-3">
          <p className="text-sb-caption text-sb-ink-mute">수정 리뷰 긍정 비율</p>
          <p className="mt-sb-1 font-sb-mono text-sb-title tabular-nums text-sb-neg-text">
            {formatRate(summary.updatedPositiveRate)}%
          </p>
        </div>
      </div>
    </section>
  )
}
