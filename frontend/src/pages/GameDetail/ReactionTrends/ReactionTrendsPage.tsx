import { useEffect, useEffectEvent, useMemo, useRef, useState } from "react"
import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import { useReactionTrends, useReactionTrendsSummary } from "@/hooks/queries/statisticsQueries"
import { AiSummaryCard } from "@/pages/GameDetail/ReactionTrends/components/AiSummaryCard"
import { ChannelPanel } from "@/pages/GameDetail/ReactionTrends/components/ChannelPanel"
import { PatchNoteModal } from "@/pages/GameDetail/ReactionTrends/components/PatchNoteModal"
import {
  DAY_WIDTH,
  ReactionTrendsChart,
} from "@/pages/GameDetail/ReactionTrends/components/ReactionTrendsChart"
import {
  formatCount,
  formatRate,
  sumDailyRange,
  toChartRows,
} from "@/pages/GameDetail/ReactionTrends/lib/aggregate"
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
): { startIndex: number; endIndex: number } {
  if (dailyLength === 0) return { startIndex: 0, endIndex: 0 }
  const startIndex = Math.max(0, Math.floor(scrollLeft / DAY_WIDTH))
  const endIndex = Math.min(dailyLength, Math.ceil((scrollLeft + clientWidth) / DAY_WIDTH))
  return { startIndex, endIndex: Math.max(startIndex + 1, endIndex) }
}

export default function ReactionTrendsPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  const [startDate, setStartDate] = useState(() => initialReactionTrendsStartDate())
  const [selectedPatchId, setSelectedPatchId] = useState<string | null>(null)
  const [modalPatchId, setModalPatchId] = useState<string | null>(null)
  const [aiRange, setAiRange] = useState<{ start: string; end: string } | null>(null)
  const [viewport, setViewport] = useState<{ startIndex: number; endIndex: number } | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const prevDayCountRef = useRef(0)
  const aiTimerRef = useRef<number | null>(null)
  const loadingMoreRef = useRef(false)

  const query = useReactionTrends(gameId, startDate)
  const daily = useMemo(() => query.data?.daily ?? [], [query.data?.daily])
  const rows = useMemo(() => toChartRows(daily), [daily])

  const summaryQuery = useReactionTrendsSummary(
    gameId,
    aiRange?.start ?? null,
    aiRange?.end ?? null,
    Boolean(aiRange),
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
    const startIndex = viewport?.startIndex ?? 0
    const endIndex = viewport?.endIndex ?? daily.length
    if (endIndex <= startIndex) return daily
    return daily.slice(startIndex, Math.min(endIndex, daily.length))
  }, [daily, viewport])

  const rangeSummary = useMemo(() => sumDailyRange(visibleDaily), [visibleDaily])
  const periodLabel =
    visibleDaily.length > 0
      ? formatDisplayRange(visibleDaily[0].date, visibleDaily.at(-1)!.date, visibleDaily.length)
      : null

  const scheduleAiFromViewport = useEffectEvent(() => {
    if (daily.length === 0) return
    const el = scrollRef.current?.querySelector("[data-chart-scroll]")
    if (!(el instanceof HTMLElement)) {
      const end = daily.at(-1)!.date
      const start = daily[Math.max(0, daily.length - 14)]!.date
      setAiRange({ start, end })
      return
    }
    const sliceIndexes = visibleSlice(daily.length, el.scrollLeft, el.clientWidth)
    const slice = daily.slice(sliceIndexes.startIndex, sliceIndexes.endIndex)
    if (slice.length === 0) return
    setAiRange({ start: slice[0].date, end: slice.at(-1)!.date })
    setViewport(sliceIndexes)
  })

  const loadEarlier = useEffectEvent(() => {
    if (!query.data || loadingMoreRef.current || query.isFetching) return
    const availableStart = query.data.availablePeriod.startDate
    const currentStart = daily[0]?.date
    if (!currentStart || currentStart <= availableStart) return
    const nextStart = addDaysIso(currentStart, -LOAD_MORE_DAYS)
    const clamped = nextStart < availableStart ? availableStart : nextStart
    if (clamped >= startDate) return
    loadingMoreRef.current = true
    setStartDate(clamped)
  })

  useEffect(() => {
    if (!query.isFetching) loadingMoreRef.current = false
  }, [query.isFetching])

  useEffect(() => {
    const prev = prevDayCountRef.current
    if (prev > 0 && daily.length > prev) {
      const el = scrollRef.current?.querySelector("[data-chart-scroll]")
      if (el instanceof HTMLElement) {
        el.scrollLeft += (daily.length - prev) * DAY_WIDTH
      }
    }
    prevDayCountRef.current = daily.length
  }, [daily.length])

  useEffect(() => {
    const root = scrollRef.current
    if (!root || daily.length === 0) return
    const el = root.querySelector("[data-chart-scroll]")
    if (!(el instanceof HTMLElement)) return

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
      setViewport(visibleSlice(daily.length, el.scrollLeft, el.clientWidth))
      queueAiAfterIdle()
      if (el.scrollLeft < LOAD_EDGE_PX) loadEarlier()
    }

    el.addEventListener("scroll", onScroll, { passive: true })
    queueAiAfterIdle()
    return () => {
      el.removeEventListener("scroll", onScroll)
      clearAiTimer()
    }
  }, [daily.length])

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
              onChange={(event) => setSelectedPatchId(event.target.value || null)}
            >
              {patches.length === 0 ? <option value="">패치 없음</option> : null}
              {patches.map((patch) => (
                <option key={patch.id} value={patch.id}>
                  {patch.title} ({patch.patchedOn})
                </option>
              ))}
            </select>
          </label>
          {resolvedPatchId ? (
            <button
              type="button"
              className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              onClick={() => setModalPatchId(resolvedPatchId)}
            >
              패치 노트 보기
            </button>
          ) : null}
          {selectedPatch ? (
            <p className="text-sb-caption text-sb-ink-mute">
              {selectedPatch.totalPatchCount}개 패치 중 {selectedPatch.patchIndex}번째
            </p>
          ) : null}
          {loadingMore ? (
            <p className="text-sb-caption text-sb-ink-mute">이전 일자 불러오는 중…</p>
          ) : null}
        </div>

        <div ref={scrollRef} className="mt-sb-4">
          <ReactionTrendsChart
            rows={rows}
            selectedPatchId={resolvedPatchId}
            onSelectDay={(date) => {
              const day = daily.find((item) => item.date === date)
              const patch = day?.patches[0]
              if (patch) {
                setSelectedPatchId(patch.id)
                setModalPatchId(patch.id)
              }
            }}
          />
        </div>
        <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">
          좌우로 스크롤하여 이전·이후 날짜를 보세요. 왼쪽 끝에서 이전 일자가 더 로드됩니다. (기준일{" "}
          {todaySeoul()})
        </p>
      </section>

      <div className="grid gap-sb-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <AiSummaryCard
          data={summaryQuery.data}
          isPending={summaryQuery.isPending}
          isError={summaryQuery.isError}
        />
        <ChannelPanel summary={rangeSummary} dayCount={visibleDaily.length || daily.length} />
      </div>

      {modalPatchId ? (
        <PatchNoteModal
          gameId={gameId}
          patchId={modalPatchId}
          onClose={() => setModalPatchId(null)}
        />
      ) : null}
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
