import { useCallback, useEffect, useEffectEvent, useMemo, useRef, useState } from "react"
import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import { useReactionTrends, useReactionTrendsSummary } from "@/hooks/queries/statisticsQueries"
import { AiSummaryCard } from "@/pages/GameDetail/ReactionTrends/components/AiSummaryCard"
import { ChannelPanel } from "@/pages/GameDetail/ReactionTrends/components/ChannelPanel"
import { PatchNotesPanel } from "@/pages/GameDetail/ReactionTrends/components/PatchNotesPanel"
import { ReactionTrendsChart } from "@/pages/GameDetail/ReactionTrends/components/ReactionTrendsChart"
import {
  formatCount,
  formatRate,
  sumDailyRange,
  toChartRows,
} from "@/pages/GameDetail/ReactionTrends/lib/aggregate"
import {
  MIN_DAY_WIDTH,
  useChartDayWidth,
  VISIBLE_DAYS,
} from "@/pages/GameDetail/ReactionTrends/lib/chartLayout"
import {
  addDaysIso,
  formatCollectedLabel,
  formatDisplayRange,
  initialReactionTrendsStartDate,
  todaySeoul,
} from "@/lib/seoulDate"
import type { ReactionTrendPatchMarker } from "@/types/statistics"

const AI_DEBOUNCE_MS = 450
const LOAD_MORE_DAYS = 30
const LOAD_EDGE_PX = 48

function parseGameId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id >= 1 ? id : null
}

function visibleSlice(
  dailyLength: number,
  scrollLeft: number,
  clientWidth: number,
  dayWidth: number,
): { startIndex: number; endIndex: number } {
  if (dailyLength === 0 || dayWidth <= 0) return { startIndex: 0, endIndex: 0 }
  const startIndex = Math.max(0, Math.floor(scrollLeft / dayWidth))
  const endIndex = Math.min(dailyLength, Math.ceil((scrollLeft + clientWidth) / dayWidth))
  return { startIndex, endIndex: Math.max(startIndex + 1, endIndex) }
}

export default function ReactionTrendsPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  const [startDate, setStartDate] = useState(() => initialReactionTrendsStartDate())
  const [selectedPatchId, setSelectedPatchId] = useState<string | null>(null)
  const [aiRange, setAiRange] = useState<{ start: string; end: string } | null>(null)
  const [viewport, setViewport] = useState<{ startIndex: number; endIndex: number } | null>(null)
  const [scrollEl, setScrollEl] = useState<HTMLDivElement | null>(null)
  const chartScrollRef = useRef<HTMLDivElement | null>(null)
  const prevDayCountRef = useRef(0)
  const needsEndPinRef = useRef(true)
  const aiTimerRef = useRef<number | null>(null)
  const loadingMoreRef = useRef(false)
  const dayWidth = useChartDayWidth(scrollEl)

  const query = useReactionTrends(gameId, startDate)
  const daily = useMemo(() => query.data?.daily ?? [], [query.data?.daily])
  const rows = useMemo(() => toChartRows(daily), [daily])

  // Prefer scroll-idle range; before first idle, use the latest VISIBLE_DAYS window.
  const resolvedAiRange = useMemo(() => {
    if (aiRange) return aiRange
    if (daily.length === 0) return null
    const end = daily.at(-1)!.date
    const start = daily[Math.max(0, daily.length - VISIBLE_DAYS)]!.date
    return { start, end }
  }, [aiRange, daily])

  const summaryQuery = useReactionTrendsSummary(
    gameId,
    resolvedAiRange?.start ?? null,
    resolvedAiRange?.end ?? null,
    Boolean(resolvedAiRange),
  )

  const patches = useMemo(() => {
    const map = new Map<string, ReactionTrendPatchMarker>()
    for (const day of daily) {
      for (const patch of day.patches) map.set(patch.id, patch)
    }
    return [...map.values()].toSorted((a, b) => a.patchedOn.localeCompare(b.patchedOn))
  }, [daily])

  const resolvedPatchId = selectedPatchId ?? patches.at(-1)?.id ?? null
  const selectedPatch = patches.find((patch) => patch.id === resolvedPatchId) ?? null

  const visibleDaily = useMemo(() => {
    if (daily.length === 0) return daily
    const startIndex = viewport?.startIndex ?? Math.max(0, daily.length - VISIBLE_DAYS)
    const endIndex = viewport?.endIndex ?? daily.length
    if (endIndex <= startIndex) return daily
    return daily.slice(startIndex, Math.min(endIndex, daily.length))
  }, [daily, viewport])

  const rangeSummary = useMemo(() => sumDailyRange(visibleDaily), [visibleDaily])
  const periodLabel =
    visibleDaily.length > 0
      ? formatDisplayRange(visibleDaily[0].date, visibleDaily.at(-1)!.date, visibleDaily.length)
      : null

  const scrollToIndex = (index: number, behavior: ScrollBehavior = "smooth") => {
    const el = chartScrollRef.current
    if (!el || dayWidth <= 0) return
    const maxLeft = Math.max(0, el.scrollWidth - el.clientWidth)
    const target = Math.min(
      maxLeft,
      Math.max(0, index * dayWidth - el.clientWidth / 2 + dayWidth / 2),
    )
    el.scrollTo({ left: target, behavior })
  }

  const scrollToLatest = (behavior: ScrollBehavior = "smooth") => {
    const el = chartScrollRef.current
    if (!el) return
    el.scrollTo({ left: Math.max(0, el.scrollWidth - el.clientWidth), behavior })
  }

  const scrollToPatch = (patchId: string) => {
    const patch = patches.find((item) => item.id === patchId)
    if (!patch) return
    const index = daily.findIndex((day) => day.date === patch.patchedOn)
    if (index < 0) return
    scrollToIndex(index)
  }

  const scheduleAiFromViewport = useEffectEvent(() => {
    if (daily.length === 0) return
    const el = chartScrollRef.current
    if (!el) {
      const end = daily.at(-1)!.date
      const start = daily[Math.max(0, daily.length - VISIBLE_DAYS)]!.date
      setAiRange({ start, end })
      return
    }
    const sliceIndexes = visibleSlice(daily.length, el.scrollLeft, el.clientWidth, dayWidth)
    const slice = daily.slice(sliceIndexes.startIndex, sliceIndexes.endIndex)
    if (slice.length === 0) return
    setAiRange({ start: slice[0].date, end: slice.at(-1)!.date })
    setViewport(sliceIndexes)
  })

  const loadEarlier = useEffectEvent(() => {
    if (!query.data?.availablePeriod || loadingMoreRef.current || query.isFetching) return
    const availableStart = query.data.availablePeriod.startDate
    const currentStart = daily[0]?.date
    if (!currentStart || currentStart <= availableStart) return
    const nextStart = addDaysIso(currentStart, -LOAD_MORE_DAYS)
    const clamped = nextStart < availableStart ? availableStart : nextStart
    if (clamped >= startDate) return
    loadingMoreRef.current = true
    setStartDate(clamped)
  })

  const bindScrollEl = useCallback((node: HTMLDivElement | null) => {
    chartScrollRef.current = node
    setScrollEl((prev) => (prev === node ? prev : node))
  }, [])

  useEffect(() => {
    if (!query.isFetching) loadingMoreRef.current = false
  }, [query.isFetching])

  useEffect(() => {
    const prev = prevDayCountRef.current
    const el = chartScrollRef.current
    if (!el || daily.length === 0) {
      prevDayCountRef.current = daily.length
      return
    }

    if (prev > 0 && daily.length > prev) {
      el.scrollLeft += (daily.length - prev) * dayWidth
      needsEndPinRef.current = false
      prevDayCountRef.current = daily.length
      return
    }

    prevDayCountRef.current = daily.length

    if (!needsEndPinRef.current) return

    const pinToEnd = () => {
      const node = chartScrollRef.current
      if (!node || !needsEndPinRef.current) return
      node.scrollLeft = Math.max(0, node.scrollWidth - node.clientWidth)
    }

    pinToEnd()
    const raf = requestAnimationFrame(pinToEnd)
    return () => cancelAnimationFrame(raf)
  }, [daily.length, dayWidth])

  useEffect(() => {
    const el = scrollEl
    if (!el || daily.length === 0) return

    const clearAiTimer = () => {
      if (aiTimerRef.current !== null) {
        window.clearTimeout(aiTimerRef.current)
        aiTimerRef.current = null
      }
    }

    const queueAiAfterIdle = () => {
      clearAiTimer()
      aiTimerRef.current = window.setTimeout(() => {
        scheduleAiFromViewport()
        aiTimerRef.current = null
      }, AI_DEBOUNCE_MS)
    }

    const onScroll = () => {
      const maxLeft = Math.max(0, el.scrollWidth - el.clientWidth)
      if (el.scrollLeft < maxLeft - 1) needsEndPinRef.current = false
      setViewport(
        visibleSlice(daily.length, el.scrollLeft, el.clientWidth, dayWidth || MIN_DAY_WIDTH),
      )
      queueAiAfterIdle()
      if (el.scrollLeft < LOAD_EDGE_PX) loadEarlier()
    }

    el.addEventListener("scroll", onScroll, { passive: true })
    queueAiAfterIdle()
    return () => {
      el.removeEventListener("scroll", onScroll)
      clearAiTimer()
    }
  }, [daily.length, dayWidth, scrollEl])

  if (gameId === null) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        잘못된 게임 주소입니다.
      </p>
    )
  }

  if (query.isPending && daily.length === 0) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        반응 추세를 불러오는 중…
      </p>
    )
  }

  if (query.isError && daily.length === 0) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-neg-text md:px-sb-12">
        {isApiError(query.error) ? query.error.message : "반응 추세를 불러오지 못했습니다."}
      </p>
    )
  }

  const collected = formatCollectedLabel(query.data?.meta.lastCollectedAt ?? null)
  const basis = query.data?.meta.aggregationBasis === "UPDATED_AT" ? "수정일 기준 집계" : null
  const loadingMore = query.isFetching && query.isPlaceholderData

  return (
    <div className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
        <header className="flex flex-wrap items-end justify-between gap-sb-3">
          <div>
            <h1 className="text-sb-title font-medium text-sb-ink">일별 리뷰 반응</h1>
            {periodLabel ? (
              <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">{periodLabel}</p>
            ) : null}
            <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
              {[collected, basis].filter(Boolean).join(" · ")}
            </p>
          </div>
          <div className="flex flex-wrap gap-sb-4 text-sb-body">
            <Metric label="긍정률" value={`${formatRate(rangeSummary.positiveRate)}%`} />
            <Metric label="리뷰 유입" value={`${formatCount(rangeSummary.reviewCount)}건`} />
            <Metric label="첫 작성" value={formatCount(rangeSummary.firstWrittenCount)} />
            <Metric label="수정" value={formatCount(rangeSummary.updatedCount)} />
          </div>
        </header>

        <div className="mt-sb-4 flex flex-wrap items-center gap-sb-3">
          <label className="flex items-center gap-sb-2 text-sb-body text-sb-ink-mute">
            패치 시점
            <select
              className="h-sb-control min-w-56 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              value={resolvedPatchId ?? ""}
              onChange={(event) => {
                const nextId = event.target.value || null
                setSelectedPatchId(nextId)
                if (nextId) scrollToPatch(nextId)
              }}
            >
              {patches.length === 0 ? <option value="">패치 없음</option> : null}
              {patches.map((patch) => (
                <option key={patch.id} value={patch.id}>
                  {patch.title} ({patch.patchedOn})
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
            onClick={() => scrollToLatest()}
          >
            최근 날짜로
          </button>
          {selectedPatch ? (
            <p className="text-sb-caption text-sb-ink-mute">
              {selectedPatch.totalPatchCount}개 패치 중 {selectedPatch.patchIndex}번째
            </p>
          ) : null}
          {loadingMore ? (
            <p className="text-sb-caption text-sb-ink-mute">이전 일자 불러오는 중…</p>
          ) : null}
        </div>

        <div className="mt-sb-4">
          <ReactionTrendsChart
            rows={rows}
            selectedPatchId={resolvedPatchId}
            dayWidth={dayWidth}
            visibleDays={VISIBLE_DAYS}
            scrollRef={bindScrollEl}
            onSelectDay={(date) => {
              const day = daily.find((item) => item.date === date)
              const patch = day?.patches[0]
              if (patch) setSelectedPatchId(patch.id)
            }}
          />
        </div>
        <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">
          한 화면에 약 {VISIBLE_DAYS}일이 보입니다. 좌우 스크롤로 이전·이후 날짜를 보고, 왼쪽 끝에서
          이전 일자가 더 로드됩니다. (기준일 {todaySeoul()})
        </p>
      </section>

      <div className="grid gap-sb-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <div className="flex flex-col gap-sb-4">
          <AiSummaryCard
            data={summaryQuery.data}
            isPending={summaryQuery.isFetching && !summaryQuery.data}
            isError={summaryQuery.isError}
            awaitingRange={!resolvedAiRange && daily.length > 0}
          />
          <PatchNotesPanel gameId={gameId} patchId={resolvedPatchId} />
        </div>
        <ChannelPanel summary={rangeSummary} dayCount={visibleDaily.length || daily.length} />
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-sb-caption text-sb-ink-mute">{label}</p>
      <p className="font-sb-mono text-sb-title tabular-nums text-sb-ink">{value}</p>
    </div>
  )
}
