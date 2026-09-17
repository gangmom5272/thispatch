import { useEffect, useRef, useState } from "react"

export const VISIBLE_DAYS = 14
export const MIN_DAY_WIDTH = 24

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
