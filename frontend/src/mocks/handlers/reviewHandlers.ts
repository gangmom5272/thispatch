import { delay, http, HttpResponse } from "msw"
import { mockGames } from "@/mocks/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { addDaysIso, todaySeoul } from "@/lib/seoulDate"
import { REVIEW_TOPICS, type Review } from "@/types/review"

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

/** 태그 id로 ReviewTag를 만든다. */
function tagOf(id: number) {
  const topic = REVIEW_TOPICS.find((t) => t.id === id)
  return { id, name: topic?.name ?? `토픽 ${id}` }
}

/**
 * 게임·시드에 따라 대표 리뷰 3건을 만든다.
 * 긍정/부정·언어·태그를 섞어 카드 UI를 검증한다.
 */
function buildRepresentative(gameId: number): Review[] {
  // seed: 게임마다 본문·수치가 달라 보이게
  const seed = gameId % 97
  const end = todaySeoul()

  return [
    {
      id: gameId * 10 + 1,
      sentiment: "NEGATIVE",
      isUpdated: true,
      playtimeMinutes: 11640 + seed * 10,
      languageCode: "english",
      helpfulCount: 412 + seed,
      tags: [tagOf(1), tagOf(3)],
      body: "상위 난이도에서 드로우가 줄면서 기존 덱 아키타입이 전부 무너졌다. 장기 플레이어 입장에선 사실상 다른 게임이 됐다.",
      reviewDate: addDaysIso(end, -2),
    },
    {
      id: gameId * 10 + 2,
      sentiment: "NEGATIVE",
      isUpdated: true,
      playtimeMinutes: 16260 + seed * 5,
      languageCode: "schinese",
      helpfulCount: 288 + (seed % 40),
      tags: [tagOf(5), tagOf(1)],
      body: "보상 재화까지 같이 줄어서 반복 플레이 동기가 사라졌다. 두 변경을 한 패치에 같이 넣은 게 문제.",
      reviewDate: addDaysIso(end, -3),
    },
    {
      id: gameId * 10 + 3,
      sentiment: "POSITIVE",
      isUpdated: false,
      playtimeMinutes: 2760 + seed,
      languageCode: seed % 2 === 0 ? "japanese" : "english",
      helpfulCount: 134 + (seed % 20),
      tags: [tagOf(2)],
      body: "치명 크래시가 드디어 고쳐졌다. 이번 패치에서 이건 확실히 좋아진 부분.",
      reviewDate: addDaysIso(end, -5),
    },
  ]
}

export const reviewHandlers = [
  http.get(`${baseURL}/games/:gameId/reviews/representative`, async ({ params, request }) => {
    await delay(200)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    return respond(200, "OK", buildRepresentative(gameId))
  }),
]
