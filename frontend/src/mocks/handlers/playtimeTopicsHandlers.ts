import { delay, http, HttpResponse } from "msw"
import { mockGames } from "@/mocks/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { addDaysIso, todaySeoul } from "@/lib/seoulDate"
import type {
  PlaytimeBandId,
  PlaytimeBandStats,
  PlaytimeTopicRow,
  PlaytimeTopics,
  PlaytimeTopicsAiSummary,
} from "@/types/statistics"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

/** MSW 공통 envelope 응답. */
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

/** 게임 전체 리뷰 기준 사분위 경계(분). */
const SCALE = {
  source: "ALL_GAME_REVIEWS" as const,
  sampleCount: 84210,
  p25Minutes: 120,
  medianMinutes: 1200,
  p75Minutes: 6000,
}

const TOPIC_NAMES = [
  "밸런스 / 너프·버프",
  "과금 / 재화(BM)",
  "최적화 / 버그·크래시",
  "시스템 / UI·편의성",
  "콘텐츠 / 엔드게임",
] as const

/** bandNo 쿼리를 PlaytimeBandId로 변환. 잘못된 값은 INVALID. */
function parseBandNo(raw: string | null): PlaytimeBandId | "INVALID" | "ALL" {
  if (raw === null || raw === "") return "ALL"
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1 || n > 4) return "INVALID"
  return `B${n}` as PlaytimeBandId
}

/** 고정 4밴드 통계(최근 14일 숫자). */
function buildBands(): PlaytimeBandStats[] {
  const { p25Minutes, medianMinutes, p75Minutes } = SCALE
  return [
    {
      band: "B1",
      minMinutes: 0,
      maxMinutesExclusive: p25Minutes,
      reviewCount: 120,
      positiveCount: 86,
      negativeCount: 34,
      positiveRate: 71.7,
      sampleSufficient: true,
    },
    {
      band: "B2",
      minMinutes: p25Minutes,
      maxMinutesExclusive: medianMinutes,
      reviewCount: 171,
      positiveCount: 129,
      negativeCount: 42,
      positiveRate: 75.4,
      sampleSufficient: true,
    },
    {
      band: "B3",
      minMinutes: medianMinutes,
      maxMinutesExclusive: p75Minutes,
      reviewCount: 129,
      positiveCount: 85,
      negativeCount: 44,
      positiveRate: 65.9,
      sampleSufficient: true,
    },
    {
      band: "B4",
      minMinutes: p75Minutes,
      maxMinutesExclusive: null,
      reviewCount: 66,
      positiveCount: 32,
      negativeCount: 34,
      positiveRate: 48.5,
      sampleSufficient: true,
    },
  ]
}

/** 전체(ALL) 통계. */
function buildOverall(bands: PlaytimeBandStats[]): PlaytimeBandStats {
  const reviewCount = bands.reduce((sum, b) => sum + b.reviewCount, 0)
  const positiveCount = bands.reduce((sum, b) => sum + b.positiveCount, 0)
  const negativeCount = bands.reduce((sum, b) => sum + b.negativeCount, 0)
  return {
    band: "ALL",
    minMinutes: 0,
    maxMinutesExclusive: null,
    reviewCount,
    positiveCount,
    negativeCount,
    positiveRate: reviewCount === 0 ? null : (positiveCount / reviewCount) * 100,
    sampleSufficient: true,
  }
}

/**
 * 토픽 행을 만든다. selected가 ALL이면 differencePp=null.
 * B4 선택 시 밸런스 언급률을 높여 흰 선과 차이를 보이게 한다.
 */
function buildTopics(selected: PlaytimeBandId): PlaytimeTopicRow[] {
  const overallRates = [43.6, 24.1, 25.7, 19.1, 16.4]
  const bandRates: Record<Exclude<PlaytimeBandId, "ALL">, number[]> = {
    B1: [28.0, 18.0, 32.0, 22.0, 14.0],
    B2: [36.0, 22.0, 24.0, 20.0, 15.0],
    B3: [48.0, 28.0, 21.0, 18.0, 17.0],
    B4: [68.2, 40.9, 22.7, 16.0, 19.0],
  }
  const rates = selected === "ALL" ? overallRates : bandRates[selected]
  const reviewBase = selected === "ALL" ? 486 : selected === "B4" ? 66 : 120

  return TOPIC_NAMES.map((name, index) => {
    const mentionRate = rates[index]!
    const overallMentionRate = overallRates[index]!
    const mentionCount = Math.round((reviewBase * mentionRate) / 100)
    return {
      topicId: index + 1,
      name,
      mentionCount,
      mentionRate,
      overallMentionRate,
      differencePp:
        selected === "ALL" ? null : Number((mentionRate - overallMentionRate).toFixed(1)),
      highestBand: {
        band: (["B4", "B4", "B1", "B1", "B3"] as const)[index]!,
        mentionRate: bandRates.B4[index]!,
      },
    }
  })
}

/** 표본 부족(B4만 부족) fallback 페이로드. */
function buildInsufficientPayload(): PlaytimeTopics {
  const endDate = todaySeoul()
  const startDate = addDaysIso(endDate, -13)
  const bands = buildBands().map((band) =>
    band.band === "B4"
      ? {
          ...band,
          reviewCount: 8,
          positiveCount: 3,
          negativeCount: 5,
          positiveRate: null,
          sampleSufficient: false,
        }
      : band,
  )
  const overall = buildOverall(bands)

  return {
    meta: {
      period: { startDate, endDate, dayCount: 14 },
      timezone: "Asia/Seoul",
      aggregationBasis: "UPDATED_AT",
      dataStatus: "AVAILABLE",
    },
    selectedBand: "B4",
    minimumSampleCount: 30,
    sampleSufficient: false,
    scale: SCALE,
    overall,
    bands,
    topics: [],
    fallback: {
      reasonCode: "INSUFFICIENT_SAMPLE",
      message: "표본이 부족해 구간·토픽 집계 대신 원문을 표시합니다.",
      totalCount: 8,
      itemsByBand: [
        {
          band: "B4",
          items: [
            {
              id: 101,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -12),
              playtimeMinutes: 11640,
              languageCode: "english",
              body: "A12 이상에서 드로우가 줄면서 기존 덱 아키타입이 전부 무너졌다. 상위 난이도를 계속 돌던 사람 입장에선 사실상 다른 게임이 됐다.",
            },
            {
              id: 102,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -11),
              playtimeMinutes: 16260,
              languageCode: "schinese",
              body: "보상 재화까지 같이 줄어서 반복 플레이 동기가 사라졌다. 두 변경을 한 패치에 같이 넣은 게 문제.",
            },
            {
              id: 103,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -8),
              playtimeMinutes: 18720,
              languageCode: "russian",
              body: "난이도 표기가 바뀌었는데 인게임 설명이 그대로라 무엇이 조정됐는지 알 수 없다.",
            },
            {
              id: 104,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -7),
              playtimeMinutes: 13680,
              languageCode: "english",
              body: "재화 획득량이 줄어든 만큼 언락에 걸리는 시간이 늘었다. 시간을 파는 구조로 바뀐 느낌.",
            },
            {
              id: 105,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -5),
              playtimeMinutes: 20160,
              languageCode: "korean",
              body: "밸런스 패치 이후 즐기던 빌드가 전부 막혀서 장기 플레이어만 피해를 본다.",
            },
            {
              id: 106,
              sentiment: "POSITIVE",
              reviewDate: addDaysIso(endDate, -4),
              playtimeMinutes: 7200,
              languageCode: "english",
              body: "초반 튜토리얼은 나아졌지만 100시간 구간 콘텐츠는 여전히 얇다.",
            },
            {
              id: 107,
              sentiment: "NEGATIVE",
              reviewDate: addDaysIso(endDate, -2),
              playtimeMinutes: 9600,
              languageCode: "korean",
              body: "엔드게임 보상 테이블이 nerf된 뒤로 주간 루틴이 끊겼다.",
            },
            {
              id: 108,
              sentiment: "NEGATIVE",
              reviewDate: endDate,
              playtimeMinutes: 14400,
              languageCode: "english",
              body: "패치 노트가 모호해서 무엇이 의도인지 커뮤니티가 추측만 하고 있다.",
            },
          ],
        },
      ],
    },
  }
}

/** 정상 표본 응답. */
function buildSufficientPayload(selected: PlaytimeBandId): PlaytimeTopics {
  const endDate = todaySeoul()
  const startDate = addDaysIso(endDate, -13)
  const bands = buildBands()
  const overall = buildOverall(bands)

  return {
    meta: {
      period: { startDate, endDate, dayCount: 14 },
      timezone: "Asia/Seoul",
      aggregationBasis: "UPDATED_AT",
      dataStatus: "AVAILABLE",
    },
    selectedBand: selected,
    minimumSampleCount: 30,
    sampleSufficient: true,
    scale: SCALE,
    overall,
    bands,
    topics: buildTopics(selected),
    fallback: null,
  }
}

export const playtimeTopicsHandlers = [
  http.get(`${baseURL}/games/:gameId/playtime-topics`, async ({ params, request }) => {
    await delay(200)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }

    const url = new URL(request.url)
    const parsed = parseBandNo(url.searchParams.get("bandNo"))
    if (parsed === "INVALID") {
      return respond(400, "유효하지 않은 bandNo입니다.")
    }

    // 1517290(Battlefield)에서 B4 선택 시 표본 부족 시나리오
    if (gameId === 1517290 && parsed === "B4") {
      return respond(200, "OK", buildInsufficientPayload())
    }

    return respond(200, "OK", buildSufficientPayload(parsed === "ALL" ? "ALL" : parsed))
  }),

  http.get(`${baseURL}/games/:gameId/summaries/playtime-topics`, async ({ params, request }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }

    const url = new URL(request.url)
    const parsed = parseBandNo(url.searchParams.get("bandNo"))
    if (parsed === "INVALID") {
      return respond(400, "유효하지 않은 bandNo입니다.")
    }

    const endDate = todaySeoul()
    const startDate = addDaysIso(endDate, -13)
    const selectedBand = parsed === "ALL" ? "ALL" : parsed

    if (gameId === 1517290 && selectedBand === "B4") {
      const skipped: PlaytimeTopicsAiSummary = {
        meta: {
          period: { startDate, endDate, dayCount: 14 },
          timezone: "Asia/Seoul",
          aggregationBasis: "UPDATED_AT",
          dataStatus: "AVAILABLE",
        },
        selectedBand: "B4",
        summary: {
          status: "SKIPPED",
          text: null,
          recurringExpressions: [],
          targetPeriod: { startDate, endDate, dayCount: 14 },
          targetReviewCount: 8,
          usedReviewCount: null,
          selection: null,
          reasonCode: "INSUFFICIENT_SAMPLE",
        },
      }
      return respond(200, "OK", skipped)
    }

    const completed: PlaytimeTopicsAiSummary = {
      meta: {
        period: { startDate, endDate, dayCount: 14 },
        timezone: "Asia/Seoul",
        aggregationBasis: "UPDATED_AT",
        dataStatus: "AVAILABLE",
      },
      selectedBand,
      summary: {
        status: "COMPLETED",
        text:
          selectedBand === "ALL"
            ? "전 구간에서 밸런스 언급이 가장 많고, 부정 비중은 플레이타임이 길수록 높아집니다. 초반은 최적화·안정성, 장기 구간은 밸런스·보상 재화가 반복됩니다."
            : "선택 구간에서는 밸런스·보상 재화 불만이 두드러집니다. 장기 플레이어일수록 패치 체감이 크게 나타납니다.",
        recurringExpressions: [
          "밸런스 조정",
          "보상 재화",
          "크래시",
          "빌드 다양성",
          "프레임 드랍",
          "튜토리얼",
        ],
        targetPeriod: { startDate, endDate, dayCount: 14 },
        targetReviewCount: selectedBand === "ALL" ? 486 : 120,
        usedReviewCount: 40,
        selection: {
          code: "HELPFUL_DESC_PER_PLAYTIME_BUCKET",
          limit: 40,
          description: "선택 구간 기준 도움됨 상위 리뷰를 사용해 요약했습니다.",
        },
        reasonCode: null,
      },
    }
    return respond(200, "OK", completed)
  }),
]
