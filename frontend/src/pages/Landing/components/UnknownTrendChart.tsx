import { useLayoutEffect, useRef, useState } from "react"
import {
  TREND_KNOWN_DAYS,
  TREND_PATCH_DAY_LABEL,
  TREND_PATCH_LABEL,
  TREND_UNKNOWN_DAY_LABELS,
} from "@/pages/Landing/demoData"

/* 세로 치수와 색은 thispatch.pen `SB / 01 진단 · 반응 추세`의 일별 칸 구성을 그대로 옮긴 값이다. */
const RATE_HEIGHT = 136
const BAND_GAP = 5
const VOL_HEIGHT = 76
const LABEL_HEIGHT = 21
const HEIGHT = RATE_HEIGHT + BAND_GAP + VOL_HEIGHT + BAND_GAP + LABEL_HEIGHT
const VOL_TOP = RATE_HEIGHT + BAND_GAP
const LABEL_BASELINE = VOL_TOP + VOL_HEIGHT + BAND_GAP + 16

const Y_AXIS_WIDTH = 36
const AXIS_GAP = 6
const RIGHT_AXIS_WIDTH = 44
const COLUMNS_X = Y_AXIS_WIDTH + AXIS_GAP
const SIDE_WIDTH = COLUMNS_X + AXIS_GAP + RIGHT_AXIS_WIDTH

/** Y축 눈금은 80%~40%를 5칸으로 나눈 pen 값과 같은 위치에 둔다. */
const RATE_TICKS = [80, 70, 60, 50, 40]
const RATE_TICK_TOP = 10.5
const RATE_TICK_BOTTOM = 125.5
const COUNT_TICKS = [40, 20, 0]
const COUNT_MAX = 40

const COL_GAP = 2
/** 칸이 넓어져도 막대는 이 폭을 넘지 않는다. */
const MAX_BAR_WIDTH = 72
const CHIP_WIDTH = 72
const CHIP_HEIGHT = 32
const MAX_MARK_SIZE = 132
const MIN_MASK_FOR_MARK = 260

/** 한 칸에 두는 최소 너비. 브라우저가 좁으면 보여 주는 일수를 줄인다. */
const WIDTH_PER_COLUMN = 105
const MIN_COLUMNS = 6
const MIN_UNKNOWN_COLUMNS = 2
const MAX_UNKNOWN_COLUMNS = 5
const MIN_KNOWN_COLUMNS = 3
const MAX_COLUMNS = TREND_KNOWN_DAYS.length + 1 + MAX_UNKNOWN_COLUMNS
const DEFAULT_WIDTH = 960

const rateY = (rate: number) =>
  RATE_TICK_TOP + ((80 - rate) / 40) * (RATE_TICK_BOTTOM - RATE_TICK_TOP)
const volHeight = (count: number) => (count / COUNT_MAX) * VOL_HEIGHT

const LEGEND = [
  { label: "긍정률", className: "bg-sb-primary rounded-full size-2.5" },
  { label: "첫 작성", className: "bg-sb-primary-deep size-2.5 rounded-sb-tag" },
  { label: "수정", className: "bg-sb-accent-violet size-2.5 rounded-sb-tag" },
  { label: "패치 적용일", className: "bg-sb-mark/20 h-2.5 w-4 rounded-sb-tag" },
]

/** 너비에 맞는 칸 수와, 그중 패치 전·후로 나눌 몫을 정한다. */
function layoutFor(width: number) {
  const columns = Math.min(MAX_COLUMNS, Math.max(MIN_COLUMNS, Math.round(width / WIDTH_PER_COLUMN)))
  const known = Math.min(
    TREND_KNOWN_DAYS.length,
    Math.max(MIN_KNOWN_COLUMNS, columns - 1 - MIN_UNKNOWN_COLUMNS),
  )
  return { columns, known, unknown: columns - 1 - known }
}

/**
 * 02 질문 섹션 — 패치 직전 며칠의 반응만 보여주고 패치일부터는 가려 둔다.
 * 반응 추세 화면의 일별 그래프와 같은 모양이며, 패치 이후 값은 갖고 있지 않다.
 * 그래프 너비에 맞춰 보여 줄 일수를 정해 칸 크기를 일정하게 유지한다.
 */
export function UnknownTrendChart() {
  const figureRef = useRef<HTMLElement>(null)
  const [width, setWidth] = useState(DEFAULT_WIDTH)

  useLayoutEffect(() => {
    const element = figureRef.current
    if (!element || typeof ResizeObserver === "undefined") return
    const observer = new ResizeObserver(([entry]) => {
      const next = entry.contentRect.width
      if (next > 0) setWidth(next)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const { columns, known, unknown } = layoutFor(width)
  const knownDays = TREND_KNOWN_DAYS.slice(-known)
  const columnsWidth = Math.max(0, width - SIDE_WIDTH)
  const pitch = (columnsWidth + COL_GAP) / columns
  const colWidth = pitch - COL_GAP
  const barWidth = Math.min(colWidth, MAX_BAR_WIDTH)

  const columnX = (index: number) => COLUMNS_X + index * pitch
  const columnCenter = (index: number) => columnX(index) + colWidth / 2
  const maskX = columnX(known)
  const columnsRight = COLUMNS_X + columnsWidth
  const unknownLabels = TREND_UNKNOWN_DAY_LABELS.slice(0, unknown)
  const maskWidth = columnsRight - maskX
  const markX = maskX + maskWidth * 0.62
  // 가림막이 좁으면 물음표가 본문 위로 올라와 어수선해진다.
  const markSize = Math.min(MAX_MARK_SIZE, maskWidth * 0.3)
  const showMark = maskWidth >= MIN_MASK_FOR_MARK

  return (
    <figure
      ref={figureRef}
      className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4"
    >
      <figcaption className="mb-sb-4 flex flex-wrap items-baseline gap-x-sb-3 gap-y-sb-1">
        <span className="text-sb-title font-medium text-sb-ink">일별 리뷰 반응</span>
        <span className="font-sb-mono text-sb-caption text-sb-ink-mute">
          패치 전후 · 수정일 기준 집계
        </span>
      </figcaption>

      <svg
        viewBox={`0 0 ${width} ${HEIGHT}`}
        className="h-auto w-full"
        role="img"
        aria-label={`패치 ${TREND_PATCH_LABEL} 직전 ${known}일의 일별 긍정률과 리뷰 유입. 패치일부터는 가려져 있어 반응을 알 수 없습니다.`}
      >
        <defs>
          {/* 패치일에서 오른쪽으로 갈수록 짙어진다. */}
          <linearGradient id="sb-trend-scrim" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="var(--color-sb-canvas-night)" stopOpacity="0.15" />
            <stop offset="0.3" stopColor="var(--color-sb-canvas-night)" stopOpacity="0.72" />
            <stop offset="0.6" stopColor="var(--color-sb-canvas-night)" stopOpacity="0.94" />
            <stop offset="1" stopColor="var(--color-sb-canvas-night)" stopOpacity="1" />
          </linearGradient>
        </defs>

        {RATE_TICKS.map((tick, index) => (
          <text
            key={tick}
            x={Y_AXIS_WIDTH}
            y={
              RATE_TICK_TOP +
              (index * (RATE_TICK_BOTTOM - RATE_TICK_TOP)) / (RATE_TICKS.length - 1) +
              5
            }
            textAnchor="end"
            className="fill-sb-ink-mute font-sb-mono text-[16px]"
          >
            {tick}%
          </text>
        ))}

        {COUNT_TICKS.map((tick, index) => (
          <text
            key={tick}
            x={width - RIGHT_AXIS_WIDTH}
            y={VOL_TOP + (index * VOL_HEIGHT) / (COUNT_TICKS.length - 1) + 12}
            className="fill-sb-ink-mute font-sb-mono text-[16px]"
          >
            {tick}건
          </text>
        ))}

        {/* 패치 적용일 칸 강조 */}
        <rect x={maskX} y="0" width={colWidth} height={HEIGHT} rx="4" className="fill-sb-mark/20" />

        {knownDays.map((day, index) => {
          const first = volHeight(day.firstWritten)
          const updated = volHeight(day.updated)
          const barX = columnCenter(index) - barWidth / 2
          return (
            <g key={day.label}>
              <rect
                x={barX}
                y={VOL_TOP + VOL_HEIGHT - first}
                width={barWidth}
                height={first}
                className="fill-sb-primary-deep"
              />
              <rect
                x={barX}
                y={VOL_TOP + VOL_HEIGHT - first - 1 - updated}
                width={barWidth}
                height={updated}
                rx="2"
                className="fill-sb-accent-violet"
              />
            </g>
          )
        })}

        <polyline
          points={knownDays
            .map((day, index) => `${columnCenter(index)},${rateY(day.positiveRate)}`)
            .join(" ")}
          fill="none"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="stroke-sb-hairline-strong"
        />
        {knownDays.map((day, index) => (
          <circle
            key={day.label}
            cx={columnCenter(index)}
            cy={rateY(day.positiveRate)}
            r="3.5"
            className="fill-sb-primary"
          />
        ))}

        {[...knownDays.map((day) => day.label), ...unknownLabels].map((label, index) => (
          <text
            key={label}
            x={columnCenter(index < known ? index : index + 1)}
            y={LABEL_BASELINE}
            textAnchor="middle"
            className="fill-sb-ink-mute font-sb-mono text-[16px]"
          >
            {label}
          </text>
        ))}

        {/* 패치일부터 오른쪽은 가린다. 패치 칸 표시는 가림막 위에 다시 그린다. */}
        <rect
          x={maskX}
          y="0"
          width={columnsRight - maskX}
          height={HEIGHT}
          fill="url(#sb-trend-scrim)"
        />
        <rect x={maskX} y="0" width={colWidth} height={HEIGHT} rx="4" className="fill-sb-mark/20" />
        <text
          x={columnCenter(known)}
          y={LABEL_BASELINE}
          textAnchor="middle"
          className="fill-sb-amber-text font-sb-mono text-[16px]"
        >
          {TREND_PATCH_DAY_LABEL}
        </text>

        <g transform={`translate(${columnCenter(known) - CHIP_WIDTH / 2 + 20}, 0)`}>
          <rect width={CHIP_WIDTH} height={CHIP_HEIGHT} rx="4" className="fill-sb-accent-yellow" />
          <text
            x={CHIP_WIDTH / 2}
            y="21"
            textAnchor="middle"
            className="fill-sb-on-primary font-sb-mono text-[16px] font-medium"
          >
            {TREND_PATCH_LABEL}
          </text>
        </g>
        {showMark ? (
          <text
            x={markX}
            y={HEIGHT / 2 + markSize * 0.3}
            textAnchor="middle"
            fontSize={markSize}
            className="fill-sb-ink-faint font-medium"
          >
            ?
          </text>
        ) : null}
      </svg>

      <ul className="mt-sb-4 flex flex-wrap items-center gap-sb-4 text-sb-caption text-sb-ink-mute">
        {LEGEND.map((item) => (
          <li key={item.label} className="flex items-center gap-sb-2">
            <span aria-hidden className={`inline-block ${item.className}`} />
            {item.label}
          </li>
        ))}
      </ul>
    </figure>
  )
}
