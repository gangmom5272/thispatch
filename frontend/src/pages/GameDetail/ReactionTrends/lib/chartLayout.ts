import { useEffect, useRef, useState } from "react"

export const VISIBLE_DAYS = 14
export const MIN_DAY_WIDTH = 24
/** 기간·스냅 판정에 쓰는 최소 가시 비율(반 이상) */
export const VISIBLE_RATIO_MIN = 0.5

/**
 * 일자 열 [index * dayWidth, (index+1) * dayWidth)과 뷰포트의 교차 비율(0~1).
 */
export function dayVisibilityRatio(
  dayIndex: number,
  scrollLeft: number,
  clientWidth: number,
  dayWidth: number,
): number {
  if (dayWidth <= 0 || clientWidth <= 0) return 0
  // 일자 열의 왼쪽·오른쪽 경계(px)
  const dayStart = dayIndex * dayWidth
  const dayEnd = dayStart + dayWidth
  const viewStart = scrollLeft
  const viewEnd = scrollLeft + clientWidth
  const overlap = Math.max(0, Math.min(dayEnd, viewEnd) - Math.max(dayStart, viewStart))
  return overlap / dayWidth
}

/**
 * 가시 비율 ≥ VISIBLE_RATIO_MIN 인 일자만 포함하는 [startIndex, endIndex) 슬라이스.
 */
export function visibleSlice(
  dailyLength: number,
  scrollLeft: number,
  clientWidth: number,
  dayWidth: number,
): { startIndex: number; endIndex: number } {
  if (dailyLength === 0 || dayWidth <= 0) return { startIndex: 0, endIndex: 0 }

  let startIndex = -1
  let endIndex = -1
  for (let i = 0; i < dailyLength; i++) {
    if (dayVisibilityRatio(i, scrollLeft, clientWidth, dayWidth) >= VISIBLE_RATIO_MIN) {
      if (startIndex < 0) startIndex = i
      // endIndex는 exclusive
      endIndex = i + 1
    }
  }

  // 어떤 일자도 반 이상 안 보이면 뷰포트 중심에 가장 일자 1칸
  if (startIndex < 0) {
    const center = Math.floor((scrollLeft + clientWidth / 2) / dayWidth)
    const idx = Math.min(dailyLength - 1, Math.max(0, center))
    return { startIndex: idx, endIndex: idx + 1 }
  }

  return { startIndex, endIndex }
}

export const CHART_COLORS = {
  rate: "#4ade80",
  firstWritten: "#3ecf8e",
  updated: "#ff6b5e",
  patchSelected: "rgba(255, 201, 60, 0.95)",
  patchOther: "rgba(90, 107, 124, 0.7)",
  grid: "#223447",
  axis: "#8a98a6",
} as const

/** Measure scroll viewport and return day width for VISIBLE_DAYS columns. */
export function useChartDayWidth(scrollEl: HTMLElement | null): number {
  const [dayWidth, setDayWidth] = useState(MIN_DAY_WIDTH)
  const frameRef = useRef(0)

  useEffect(() => {
    if (!scrollEl) return
    const update = () => {
      cancelAnimationFrame(frameRef.current)
      frameRef.current = requestAnimationFrame(() => {
        const w = scrollEl.clientWidth
        if (w <= 0) return
        setDayWidth(Math.max(MIN_DAY_WIDTH, Math.floor(w / VISIBLE_DAYS)))
      })
    }
    update()
    const ro = new ResizeObserver(update)
    ro.observe(scrollEl)
    return () => {
      cancelAnimationFrame(frameRef.current)
      ro.disconnect()
    }
  }, [scrollEl])

  return dayWidth
}
