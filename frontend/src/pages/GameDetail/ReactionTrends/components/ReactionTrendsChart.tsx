import { useCallback, useMemo, useRef } from "react"
import { createPortal } from "react-dom"
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import type { TooltipContentProps } from "recharts"
import type { ChartRow } from "@/pages/GameDetail/ReactionTrends/lib/aggregate"
import { CHART_COLORS } from "@/pages/GameDetail/ReactionTrends/lib/chartLayout"
import { formatShortMd } from "@/lib/seoulDate"

const CHART_HEIGHT = 320
const MARGIN_TOP = 12
const MARGIN_BOTTOM = 28
const LEFT_AXIS_WIDTH = 68
const RIGHT_AXIS_WIDTH = 72
const AXIS_LABEL_COL = 16
const TOP_BAND = 0.42
const BOTTOM_BAND = 0.58
const RATE_MIN = 40
const RATE_MAX = 90
const PLOT_HEIGHT = CHART_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM
const TOP_BAND_PX = Math.round(PLOT_HEIGHT * TOP_BAND)
const BOTTOM_BAND_PX = PLOT_HEIGHT - TOP_BAND_PX

const chartShellClass =
  "outline-none focus:outline-none focus-visible:outline-none [&_svg]:outline-none [&_svg:focus]:outline-none [&_*:focus]:outline-none"

interface ReactionTrendsChartProps {
  rows: ChartRow[]
  selectedPatchId: string | null
  dayWidth: number
  visibleDays: number
  onSelectDay?: (date: string) => void
  scrollRef?: (node: HTMLDivElement | null) => void
}

interface PlotRow extends ChartRow {
  firstWrittenPlot: number
  updatedPlot: number
  ratePlot: number | null
}

function countMaxOf(rows: ChartRow[]): number {
  let max = 0
  for (const row of rows) {
    max = Math.max(max, row.firstWrittenCount + row.updatedCount)
  }
  return Math.max(10, Math.ceil(max * 1.15))
}

function toPlotRows(rows: ChartRow[], countMax: number): PlotRow[] {
  return rows.map((row) => ({
    ...row,
    firstWrittenPlot: (row.firstWrittenCount / countMax) * BOTTOM_BAND,
    updatedPlot: (row.updatedCount / countMax) * BOTTOM_BAND,
    ratePlot:
      row.positiveRate === null
        ? null
        : BOTTOM_BAND +
          ((Math.min(RATE_MAX, Math.max(RATE_MIN, row.positiveRate)) - RATE_MIN) /
            (RATE_MAX - RATE_MIN)) *
            TOP_BAND,
  }))
}

function rateTicks(): number[] {
  return [90, 80, 70, 60, 50, 40]
}

function countTicks(countMax: number): number[] {
  const steps = 4
  const values: number[] = []
  for (let i = steps; i >= 0; i--) {
    values.push(Math.round((countMax * i) / steps))
  }
  return values
}

export function ReactionTrendsChart({
  rows,
  selectedPatchId,
  dayWidth,
  visibleDays,
  onSelectDay,
  scrollRef,
}: ReactionTrendsChartProps) {
  const plotWidth = Math.max(rows.length * dayWidth, dayWidth * visibleDays)
  const barSize = Math.max(12, dayWidth - 11)
  const countMax = useMemo(() => countMaxOf(rows), [rows])
  const plotRows = useMemo(() => toPlotRows(rows, countMax), [rows, countMax])
  const patchLines = rows.flatMap((row) =>
    row.patches.map((patch) => ({
      date: row.date,
      id: patch.id,
      title: patch.title,
      selected: patch.id === selectedPatchId,
    })),
  )

  const scrollElRef = useRef<HTMLDivElement | null>(null)
  const lockedScrollLeftRef = useRef(0)
  const allowUserScrollRef = useRef(false)
  const pointerInsideRef = useRef(false)
  const pointerClientRef = useRef({ x: 0, y: 0 })

  const attachScrollEl = useCallback(
    (node: HTMLDivElement | null) => {
      scrollElRef.current = node
      scrollRef?.(node)
    },
    [scrollRef],
  )

  const onPointerEnter = () => {
    const el = scrollElRef.current
    if (!el) return
    pointerInsideRef.current = true
    lockedScrollLeftRef.current = el.scrollLeft
  }

  const onPointerLeave = () => {
    pointerInsideRef.current = false
    allowUserScrollRef.current = false
  }

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    pointerClientRef.current = { x: event.clientX, y: event.clientY }
  }

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    allowUserScrollRef.current = true
    pointerClientRef.current = { x: event.clientX, y: event.clientY }
    const el = scrollElRef.current
    if (el) lockedScrollLeftRef.current = el.scrollLeft
    const target = event.target
    if (target instanceof Element && target.closest("svg")) {
      event.preventDefault()
    }
  }

  const onWheel = () => {
    allowUserScrollRef.current = true
  }

  const onScroll = () => {
    const el = scrollElRef.current
    if (!el) return
    const active = document.activeElement
    const focusInside = active instanceof Element && el.contains(active) && active !== el
    if (pointerInsideRef.current && focusInside && !allowUserScrollRef.current) {
      if (el.scrollLeft !== lockedScrollLeftRef.current) {
        el.scrollLeft = lockedScrollLeftRef.current
      }
      if (active instanceof HTMLElement) active.blur()
      return
    }
    lockedScrollLeftRef.current = el.scrollLeft
  }

  return (
    <div className={`flex w-full flex-col gap-sb-2 ${chartShellClass}`}>
      <div className="flex w-full items-stretch" style={{ height: CHART_HEIGHT }}>
        <RateAxisColumn />

        <div
          ref={attachScrollEl}
          tabIndex={-1}
          data-chart-scroll
          className={`min-w-0 flex-1 overflow-x-auto overflow-y-hidden overscroll-x-contain ${chartShellClass}`}
          style={{ height: CHART_HEIGHT }}
          onPointerEnter={onPointerEnter}
          onPointerLeave={onPointerLeave}
          onPointerMove={onPointerMove}
          onPointerDown={onPointerDown}
          onWheel={onWheel}
          onScroll={onScroll}
        >
          <div style={{ width: plotWidth, minWidth: "100%", height: CHART_HEIGHT }}>
            <div data-chart-plot className="h-full w-full">
              <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
                <ComposedChart
                  data={plotRows}
                  margin={{ top: MARGIN_TOP, right: 8, left: 8, bottom: MARGIN_BOTTOM }}
                  style={{ outline: "none" }}
                  onClick={(state) => {
                    const date = state?.activeLabel
                    if (typeof date === "string") onSelectDay?.(date)
                  }}
                >
                  <CartesianGrid
                    stroke={CHART_COLORS.grid}
                    strokeDasharray="3 3"
                    vertical={false}
                  />
                  <XAxis
                    dataKey="date"
                    tickFormatter={(value: string) => formatShortMd(value)}
                    tick={{ fill: CHART_COLORS.axis, fontSize: 12 }}
                    interval="preserveStartEnd"
                    minTickGap={24}
                  />
                  <YAxis yAxisId="plot" domain={[0, 1]} hide />
                  <Tooltip
                    cursor={false}
                    wrapperStyle={{ outline: "none", pointerEvents: "none", visibility: "hidden" }}
                    content={(props) => (
                      <PortalTooltip {...props} pointerClient={pointerClientRef.current} />
                    )}
                  />
                  <Bar
                    yAxisId="plot"
                    dataKey="firstWrittenPlot"
                    stackId="vol"
                    fill={CHART_COLORS.firstWritten}
                    barSize={barSize}
                    name="firstWrittenCount"
                    isAnimationActive={false}
                    activeBar={false}
                  />
                  <Bar
                    yAxisId="plot"
                    dataKey="updatedPlot"
                    stackId="vol"
                    fill={CHART_COLORS.updated}
                    barSize={barSize}
                    name="updatedCount"
                    isAnimationActive={false}
                    activeBar={false}
                  />
                  <Line
                    yAxisId="plot"
                    type="monotone"
                    dataKey="ratePlot"
                    stroke={CHART_COLORS.rate}
                    strokeWidth={2}
                    dot={{ r: 3, fill: CHART_COLORS.rate, strokeWidth: 0 }}
                    activeDot={{ r: 5, fill: CHART_COLORS.rate, strokeWidth: 0 }}
                    name="positiveRate"
                    connectNulls
                    isAnimationActive={false}
                  />
                  {patchLines.map((line) => (
                    <ReferenceLine
                      key={`${line.id}-${line.date}`}
                      yAxisId="plot"
                      x={line.date}
                      stroke={line.selected ? CHART_COLORS.patchSelected : CHART_COLORS.patchOther}
                      strokeWidth={line.selected ? 5 : 3}
                      strokeDasharray={line.selected ? undefined : "4 4"}
                      label={{
                        value: shortPatchTitle(line.title),
                        position: "insideTopLeft",
                        fill: line.selected ? CHART_COLORS.patchSelected : CHART_COLORS.axis,
                        fontSize: 11,
                      }}
                    />
                  ))}
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>

        <CountAxisColumn countMax={countMax} />
      </div>

      <ChartLegend />
    </div>
  )
}

function RateAxisColumn() {
  const ticks = rateTicks()
  const labelTop = MARGIN_TOP + TOP_BAND_PX / 2
  return (
    <div
      className={`relative flex shrink-0 ${chartShellClass}`}
      style={{ width: LEFT_AXIS_WIDTH, height: CHART_HEIGHT }}
      tabIndex={-1}
      aria-hidden
    >
      <div className="relative shrink-0" style={{ width: AXIS_LABEL_COL, height: CHART_HEIGHT }}>
        <p
          className="absolute left-1/2 origin-center -translate-x-1/2 -translate-y-1/2 -rotate-90 whitespace-nowrap text-sb-caption text-sb-ink-mute"
          style={{ top: labelTop }}
        >
          긍정률
        </p>
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <div style={{ height: MARGIN_TOP }} />
        <div
          className="flex flex-col justify-between pr-sb-1 text-right font-sb-mono text-[11px] tabular-nums text-sb-ink-mute"
          style={{ height: TOP_BAND_PX }}
        >
          {ticks.map((tick) => (
            <span key={tick}>{tick}%</span>
          ))}
        </div>
        <div style={{ height: BOTTOM_BAND_PX }} />
        <div style={{ height: MARGIN_BOTTOM }} />
      </div>
    </div>
  )
}

function CountAxisColumn({ countMax }: { countMax: number }) {
  const ticks = countTicks(countMax)
  const labelBottom = MARGIN_BOTTOM + BOTTOM_BAND_PX / 2
  return (
    <div
      className={`relative flex shrink-0 ${chartShellClass}`}
      style={{ width: RIGHT_AXIS_WIDTH, height: CHART_HEIGHT }}
      tabIndex={-1}
      aria-hidden
    >
      <div className="flex min-w-0 flex-1 flex-col">
        <div style={{ height: MARGIN_TOP }} />
        <div style={{ height: TOP_BAND_PX }} />
        <div
          className="flex flex-col justify-between pl-sb-1 text-left font-sb-mono text-[11px] tabular-nums text-sb-ink-mute"
          style={{ height: BOTTOM_BAND_PX }}
        >
          {ticks.map((tick, index) => (
            <span key={`${tick}-${index}`}>{tick.toLocaleString("en-US")}건</span>
          ))}
        </div>
        <div style={{ height: MARGIN_BOTTOM }} />
      </div>
      <div className="relative shrink-0" style={{ width: AXIS_LABEL_COL, height: CHART_HEIGHT }}>
        <p
          className="absolute left-1/2 origin-center -translate-x-1/2 translate-y-1/2 rotate-90 whitespace-nowrap text-sb-caption text-sb-ink-mute"
          style={{ bottom: labelBottom }}
        >
          리뷰
        </p>
      </div>
    </div>
  )
}

function shortPatchTitle(title: string): string {
  const match = title.match(/v?\d+\.\d+(?:\.\d+)?/i)
  if (match) {
    const raw = match[0]
    return /^v/i.test(raw) ? raw : `v${raw}`
  }
  return title.length > 12 ? `${title.slice(0, 12)}…` : title
}

function PortalTooltip({
  active,
  payload,
  label,
  coordinate,
  pointerClient,
}: TooltipContentProps & {
  pointerClient: { x: number; y: number }
}) {
  if (!active || !payload?.length || !coordinate) return null

  const scroll = document.querySelector("[data-chart-scroll]")
  const scrollRect = scroll?.getBoundingClientRect()
  if (!scrollRect || !(scroll instanceof HTMLElement)) return null

  const row = payload[0]?.payload as PlotRow | undefined
  if (!row) return null

  // Pointer client coords stay correct when the scrolled plot is wider than the viewport.
  // Fall back to scroll-adjusted chart coordinates if pointer has not been recorded.
  const fromChartX = scrollRect.left + coordinate.x - scroll.scrollLeft
  const fromChartY = scrollRect.top + coordinate.y
  const usePointer =
    pointerClient.x > scrollRect.left &&
    pointerClient.x < scrollRect.right &&
    pointerClient.y > scrollRect.top &&
    pointerClient.y < scrollRect.bottom
  const rawLeft = usePointer ? pointerClient.x : fromChartX
  const rawTop = usePointer ? pointerClient.y : fromChartY

  const flipLeft = rawLeft > window.innerWidth - 180
  const left = Math.min(Math.max(8, rawLeft), window.innerWidth - 8)
  const top = Math.max(8, rawTop)

  const items: Array<{ name: string; value: string }> = []
  if (row.positiveRate !== null) {
    items.push({ name: "긍정률", value: `${row.positiveRate.toFixed(1)}%` })
  }
  items.push({
    name: "첫 작성",
    value: `${row.firstWrittenCount.toLocaleString("en-US")}건`,
  })
  items.push({
    name: "수정",
    value: `${row.updatedCount.toLocaleString("en-US")}건`,
  })

  return createPortal(
    <div
      className="pointer-events-none rounded-sb-card border border-sb-hairline-cool bg-sb-canvas px-sb-3 py-sb-2 text-sb-caption text-sb-ink shadow-sb-popover"
      style={{
        position: "fixed",
        left,
        top,
        transform: flipLeft
          ? "translate(calc(-80% - 8px), calc(-80% - 8px))"
          : "translate(-50%, calc(-100% - 8px))",
        zIndex: 70,
      }}
    >
      <p className="mb-sb-1 font-sb-mono text-sb-ink-mute">{String(label)}</p>
      <ul className="flex flex-col gap-0.5">
        {items.map((item) => (
          <li key={item.name} className="flex justify-between gap-sb-4">
            <span className="text-sb-ink-mute">{item.name}</span>
            <span className="font-sb-mono tabular-nums">{item.value}</span>
          </li>
        ))}
      </ul>
    </div>,
    document.body,
  )
}

function ChartLegend() {
  return (
    <ul className="flex flex-wrap items-center justify-center gap-sb-4 text-sb-caption text-sb-ink-mute">
      <li className="flex items-center gap-sb-2">
        <span
          className="inline-block size-3 rounded-sb-tag"
          style={{ background: CHART_COLORS.firstWritten }}
        />
        첫 작성
      </li>
      <li className="flex items-center gap-sb-2">
        <span className="inline-block h-0.5 w-4" style={{ background: CHART_COLORS.rate }} />
        긍정률
      </li>
      <li className="flex items-center gap-sb-2">
        <span
          className="inline-block size-3 rounded-sb-tag"
          style={{ background: CHART_COLORS.updated }}
        />
        수정
      </li>
    </ul>
  )
}
