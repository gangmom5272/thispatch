import { delay, http, HttpResponse } from "msw"
import { mockGames } from "@/mocks/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import type {
  PatchDetail,
  ReactionTrendDaily,
  ReactionTrendPatchMarker,
  ReactionTrends,
} from "@/types/statistics"
import { addDaysIso, formatSeoulDate } from "@/lib/seoulDate"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

function respond(status: number, message: string, data?: unknown) {
  return HttpResponse.json(
    {
      code: String(status),
      message,
      responsedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
      ...(data === undefined ? {} : { data }),
      success: status === 200,
    },
    { status },
  )
}

function isAuthorized(request: Request) {
  return userFromAuthHeader(request) !== null
}

const PATCHES: ReactionTrendPatchMarker[] = [
  {
    id: "1001",
    title: "v1.3.2 Hotfix",
    patchedOn: "",
    patchIndex: 5,
    totalPatchCount: 7,
  },
  {
    id: "1002",
    title: "v1.4.0 Balance Adjustment",
    patchedOn: "",
    patchIndex: 6,
    totalPatchCount: 7,
  },
  {
    id: "1003",
    title: "v1.4.1 Stability",
    patchedOn: "",
    patchIndex: 7,
    totalPatchCount: 7,
  },
]

function buildDailySeries(endDate: string, totalDays: number): ReactionTrendDaily[] {
  const patchOffsets = [totalDays - 50, totalDays - 28, totalDays - 12]
  const days: ReactionTrendDaily[] = []

  for (let i = 0; i < totalDays; i++) {
    const date = addDaysIso(endDate, -(totalDays - 1 - i))
    const wave = Math.sin(i / 7) * 12
    const reviewCount = Math.max(40, Math.round(90 + wave + (i % 5) * 6))
    const negativeCount = Math.round(reviewCount * (0.28 + (i % 9) * 0.01))
    const positiveCount = reviewCount - negativeCount
    const firstWrittenCount = Math.round(reviewCount * 0.58)
    const updatedCount = reviewCount - firstWrittenCount
    const firstWrittenPositiveCount = Math.round(firstWrittenCount * 0.74)
    const updatedPositiveCount = Math.round(updatedCount * 0.62)
    const patches = patchOffsets.flatMap((offset, idx) => {
      if (i !== offset) return []
      const base = PATCHES[idx]
      return [{ ...base, patchedOn: date }]
    })

    days.push({
      date,
      dataAvailable: true,
      reviewCount,
      positiveCount,
      negativeCount,
      positiveRate: (positiveCount / reviewCount) * 100,
      firstWrittenCount,
      updatedCount,
      firstWrittenPositiveCount,
      firstWrittenNegativeCount: firstWrittenCount - firstWrittenPositiveCount,
      updatedPositiveCount,
      updatedNegativeCount: updatedCount - updatedPositiveCount,
      patches,
    })
  }
  return days
}

function summarize(daily: ReactionTrendDaily[]) {
  let reviewCount = 0
  let positiveCount = 0
  let negativeCount = 0
  let firstWrittenCount = 0
  let firstWrittenPositiveCount = 0
  let firstWrittenNegativeCount = 0
  let updatedCount = 0
  let updatedPositiveCount = 0
  let updatedNegativeCount = 0
  for (const day of daily) {
    reviewCount += day.reviewCount
    positiveCount += day.positiveCount
    negativeCount += day.negativeCount
    firstWrittenCount += day.firstWrittenCount
    firstWrittenPositiveCount += day.firstWrittenPositiveCount
    firstWrittenNegativeCount += day.firstWrittenNegativeCount
    updatedCount += day.updatedCount
    updatedPositiveCount += day.updatedPositiveCount
    updatedNegativeCount += day.updatedNegativeCount
  }
  const rate = (pos: number, total: number) => (total === 0 ? null : (pos / total) * 100)
  return {
    reviewCount,
    positiveCount,
    negativeCount,
    positiveRate: rate(positiveCount, reviewCount),
    firstWrittenCount,
    firstWrittenPositiveCount,
    firstWrittenNegativeCount,
    firstWrittenPositiveRate: rate(firstWrittenPositiveCount, firstWrittenCount),
    updatedCount,
    updatedPositiveCount,
    updatedNegativeCount,
    updatedPositiveRate: rate(updatedPositiveCount, updatedCount),
  }
}

const seriesCache = new Map<number, ReactionTrendDaily[]>()

function seriesForGame(gameId: number): ReactionTrendDaily[] {
  let series = seriesCache.get(gameId)
  if (!series) {
    const end = formatSeoulDate(new Date())
    series = buildDailySeries(end, 120)
    seriesCache.set(gameId, series)
  }
  return series
}

function buildTrends(gameId: number, startDate: string, endDate: string): ReactionTrends {
  const series = seriesForGame(gameId)
  const all = series.filter((day) => day.date <= endDate)
  const availableStart = all[0]?.date ?? startDate
  const availableEnd = all.at(-1)?.date ?? startDate
  const daily = all.filter((day) => day.date >= startDate)
  return {
    meta: {
      period: {
        startDate: daily[0]?.date ?? startDate,
        endDate,
        dayCount: daily.length,
      },
      timezone: "Asia/Seoul",
      aggregationBasis: "UPDATED_AT",
      lastCollectedAt: `${series.at(-1)?.date ?? availableEnd}T04:00:00Z`,
      dataStatus: "AVAILABLE",
    },
    availablePeriod: {
      startDate: availableStart,
      endDate: availableEnd,
      dayCount: all.length,
    },
    summary: summarize(daily),
    daily,
  }
}

const patchBodies: Record<string, string> = {
  "1001": "Hotfix\n- Crash on map load fixed\n- Memory leak in inventory",
  "1002":
    "Balance\n- Warden health +35%\n- Relic drop rate adjusted\n\nEconomy\n- Gold sink rebalanced\n\nBug Fixes\n- Soft lock in Act 2",
  "1003": "Stability\n- Network retry backoff\n- Save corruption guard",
}

export const reactionTrendsHandlers = [
  http.get(`${baseURL}/games/:gameId/reaction-trends`, async ({ request, params }) => {
    await delay(200)
    if (!isAuthorized(request)) return respond(401, "인증이 필요합니다.")
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    const url = new URL(request.url)
    const startDate = url.searchParams.get("startDate")
    const today = formatSeoulDate(new Date())
    const endDate = url.searchParams.get("endDate") ?? today
    if (!startDate || !/^\d{4}-\d{2}-\d{2}$/.test(startDate)) {
      return respond(400, "시작 날짜가 올바르지 않습니다.")
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(endDate) || endDate < startDate || endDate > today) {
      return respond(400, "종료 날짜가 올바르지 않습니다.")
    }
    return respond(200, "성공했습니다.", buildTrends(gameId, startDate, endDate))
  }),

  http.get(`${baseURL}/games/:gameId/summaries/reaction-trends`, async ({ request, params }) => {
    await delay(350)
    if (!isAuthorized(request)) return respond(401, "인증이 필요합니다.")
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    const url = new URL(request.url)
    const startDate = url.searchParams.get("startDate")
    const endDate = url.searchParams.get("endDate")
    if (!startDate || !endDate) {
      return respond(400, "기간이 올바르지 않습니다.")
    }
    const all = seriesForGame(gameId)
    const slice = all.filter((day) => day.date >= startDate && day.date <= endDate)
    const summary = summarize(slice)
    const insufficient = summary.reviewCount < 80
    return respond(200, "성공했습니다.", {
      meta: {
        period: {
          startDate,
          endDate,
          dayCount: slice.length,
        },
        timezone: "Asia/Seoul",
        aggregationBasis: "UPDATED_AT",
        lastCollectedAt: `${all.at(-1)?.date ?? endDate}T04:00:00Z`,
        dataStatus: "AVAILABLE",
      },
      summary: insufficient
        ? {
            status: "SKIPPED",
            text: null,
            targetPeriod: { startDate, endDate, dayCount: slice.length },
            reasonCode: "INSUFFICIENT_SAMPLE",
          }
        : {
            status: "COMPLETED",
            text: "수정 리뷰 비중이 평소 대비 늘었고, 수정 채널 긍정률이 첫 작성보다 낮습니다. 두 관측은 함께 나타났을 뿐 인과관계가 아닙니다.",
            targetPeriod: { startDate, endDate, dayCount: slice.length },
            reasonCode: null,
          },
    })
  }),

  http.get(`${baseURL}/games/:gameId/patches/:patchId`, async ({ request, params }) => {
    await delay(180)
    if (!isAuthorized(request)) return respond(401, "인증이 필요합니다.")
    const gameId = Number(params.gameId)
    const patchId = String(params.patchId)
    const marker = seriesForGame(gameId)
      .flatMap((day) => day.patches)
      .find((patch) => patch.id === patchId)
    if (!marker) return respond(404, "패치를 찾을 수 없습니다.")
    const data: PatchDetail = {
      patchId,
      gameId,
      title: marker.title,
      patchedOn: marker.patchedOn,
      publishedAt: `${marker.patchedOn}T09:00:00Z`,
      body: patchBodies[patchId] ?? marker.title,
      bodyFormat: "PLAIN_TEXT",
      url: null,
    }
    return respond(200, "성공했습니다.", data)
  }),
]
