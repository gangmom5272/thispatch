import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import type { ChartRow } from "@/pages/GameDetail/ReactionTrends/lib/aggregate"
import { formatShortMd } from "@/lib/seoulDate"

export const DAY_WIDTH = 32
const CHART_HEIGHT = 240

const COLORS = {
  rate: "#4ade80",
  firstWritten: "#3ecf8e",
  updated: "#a36cd8",
  patchSelected: "#ffc93c",
  patchOther: "#5a6b7c",
  grid: "#223447",
  axis: "#8a98a6",
}

interface ReactionTrendsChartProps {
  rows: ChartRow[]
  selectedPatchId: string | null
  onSelectDay?: (date: string) => void
}

export function ReactionTrendsChart({
  rows,
  selectedPatchId,
  onSelectDay,
}: ReactionTrendsChartProps) {
  const width = Math.max(rows.length * DAY_WIDTH, 640)
  const patchLines = rows.flatMap((row) =>
    row.patches.map((patch) => ({
      date: row.date,
      id: patch.id,
      selected: patch.id === selectedPatchId,
    })),
  )

  return (
    <div className="w-full overflow-x-auto" data-chart-scroll>
      <div style={{ width, minWidth: "100%" }}>
        <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
          <ComposedChart
            data={rows}
            margin={{ top: 12, right: 48, left: 8, bottom: 8 }}
            onClick={(state) => {
              const date = state?.activeLabel
              if (typeof date === "string") onSelectDay?.(date)
            }}
          >
            <CartesianGrid stroke={COLORS.grid} strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="date"
              tickFormatter={(value: string) => formatShortMd(value)}
              tick={{ fill: COLORS.axis, fontSize: 12 }}
              interval="preserveStartEnd"
              minTickGap={24}
            />
            <YAxis
              yAxisId="rate"
              domain={[40, 90]}
              tick={{ fill: COLORS.axis, fontSize: 12 }}
              tickFormatter={(v: number) => `${v}%`}
              width={40}
            />
            <YAxis
              yAxisId="count"
              orientation="right"
              tick={{ fill: COLORS.axis, fontSize: 12 }}
              width={44}
            />
            <Tooltip
              contentStyle={{
                background: "#1b2838",
                border: "1px solid #2a475e",
                borderRadius: 8,
                color: "#fff",
              }}
              labelFormatter={(label) => String(label)}
              formatter={(value, name) => {
                const label =
                  name === "positiveRate"
                    ? "긍정률"
                    : name === "firstWrittenCount"
                      ? "첫 작성"
                      : name === "updatedCount"
                        ? "수정"
                        : String(name)
                if (name === "positiveRate") {
                  return [`${Number(value).toFixed(1)}%`, label]
                }
                return [Number(value).toLocaleString("en-US"), label]
              }}
            />
            <Legend
              wrapperStyle={{ color: "#9fadba", fontSize: 14 }}
              formatter={(value) =>
                value === "positiveRate"
                  ? "긍정률"
                  : value === "firstWrittenCount"
                    ? "첫 작성"
                    : value === "updatedCount"
                      ? "수정"
                      : value
              }
            />
            <Bar
              yAxisId="count"
              dataKey="firstWrittenCount"
              stackId="vol"
              fill={COLORS.firstWritten}
              barSize={14}
              name="firstWrittenCount"
            />
            <Bar
              yAxisId="count"
              dataKey="updatedCount"
              stackId="vol"
              fill={COLORS.updated}
              barSize={14}
              name="updatedCount"
            />
            <Line
              yAxisId="rate"
              type="monotone"
              dataKey="positiveRate"
              stroke={COLORS.rate}
              strokeWidth={2}
              dot={false}
              name="positiveRate"
              connectNulls
            />
            {patchLines.map((line) => (
              <ReferenceLine
                key={`${line.id}-${line.date}`}
                yAxisId="rate"
                x={line.date}
                stroke={line.selected ? COLORS.patchSelected : COLORS.patchOther}
                strokeWidth={line.selected ? 2 : 1}
                strokeDasharray={line.selected ? undefined : "4 4"}
              />
            ))}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
