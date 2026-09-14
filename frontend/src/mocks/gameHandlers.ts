import { delay, http, HttpResponse } from "msw"
import { DEFAULT_GAME_SORT, isGameSort } from "../constants/games"
import { MOCK_ACCESS_TOKEN } from "../lib/mockConfig"
import type { Game, GameSort } from "../types"
import { mockGames } from "./games"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")
const registeredGames = new Set(mockGames.filter((game) => game.isMine).map((game) => game.id))
const invalidFiltersMessage = "검색 조건 또는 페이지 커서가 올바르지 않습니다."

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
  return request.headers.get("Authorization") === `Bearer ${MOCK_ACCESS_TOKEN}`
}

function readCursor(cursor: string, filterKey: string): number {
  const decoded: unknown = JSON.parse(atob(cursor))
  if (
    typeof decoded !== "object" ||
    decoded === null ||
    !("id" in decoded) ||
    typeof decoded.id !== "number" ||
    !Number.isSafeInteger(decoded.id) ||
    !("filterKey" in decoded) ||
    decoded.filterKey !== filterKey
  ) {
    throw new Error(invalidFiltersMessage)
  }
  return decoded.id
}

export const gameHandlers = [
  http.get(`${baseURL}/games`, async ({ request }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }

    const params = new URL(request.url).searchParams
    const search = params.get("search") ?? ""
    const sort = params.get("sort") ?? DEFAULT_GAME_SORT
    const rawLimit = params.get("limit") ?? "10"
    const limit = Number(rawLimit)
    const rawGenres = params.get("genreIds")
    const genreIds = rawGenres === null ? [] : rawGenres.split(",").map(Number)
    if (
      search.length > 100 ||
      !isGameSort(sort) ||
      !/^\d+$/.test(rawLimit) ||
      limit < 1 ||
      limit > 100 ||
      (rawGenres !== null && !/^\d+(,\d+)*$/.test(rawGenres)) ||
      genreIds.some((id) => !Number.isSafeInteger(id) || id < 1)
    ) {
      return respond(400, invalidFiltersMessage)
    }

    const comparators: Record<
      GameSort,
      (a: (typeof mockGames)[number], b: (typeof mockGames)[number]) => number
    > = {
      POSITIVE_RATE_ASC: (a, b) => a.positiveRate - b.positiveRate,
      REVIEW_COUNT_DESC: (a, b) => b.reviewCount - a.reviewCount,
      REACTION_CHANGE_DESC: (a, b) => b.reactionChange - a.reactionChange,
      RELEASE_DATE_DESC: (a, b) => b.releasedAt.localeCompare(a.releasedAt),
    }
    const matches = mockGames
      .filter(
        (game) =>
          game.title.toLowerCase().includes(search.trim().toLowerCase()) &&
          (genreIds.length === 0 || game.tags.some((tag) => genreIds.includes(tag.id))),
      )
      .toSorted((a, b) => comparators[sort](a, b) || a.id - b.id)

    const filterKey = encodeURIComponent(
      JSON.stringify([search, sort, genreIds.toSorted((a, b) => a - b)]),
    )
    let offset = 0
    const cursor = params.get("cursor")
    if (cursor !== null) {
      try {
        const id = readCursor(cursor, filterKey)
        const index = matches.findIndex((game) => game.id === id)
        if (index < 0) {
          return respond(400, invalidFiltersMessage)
        }
        offset = index + 1
      } catch {
        return respond(400, invalidFiltersMessage)
      }
    }

    const items: Game[] = matches.slice(offset, offset + limit).map((game) => ({
      id: game.id,
      capsuleImageUrl: game.capsuleImageUrl,
      title: game.title,
      tags: game.tags,
      positiveRate: game.positiveRate,
      isMine: registeredGames.has(game.id),
      gameSummary: game.gameSummary,
    }))
    const hasNext = offset + items.length < matches.length
    const lastItem = items.at(-1)
    return respond(200, "성공했습니다.", {
      items,
      page: {
        limit,
        totalCount: matches.length,
        hasNext,
        nextCursor:
          hasNext && lastItem ? btoa(JSON.stringify({ id: lastItem.id, filterKey })) : null,
      },
    })
  }),
  http.delete(`${baseURL}/games/:gameId/my-game`, async ({ request, params }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "내 게임을 등록하려면 로그인해 주세요.")
    }
    const id = Number(params.gameId)
    if (!mockGames.some((game) => game.id === id)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    registeredGames.delete(id)
    return respond(200, "성공했습니다.")
  }),
  http.post(`${baseURL}/games/:gameId/my-game`, async ({ request, params }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "내 게임을 등록하려면 로그인해 주세요.")
    }
    const id = Number(params.gameId)
    if (!mockGames.some((game) => game.id === id)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    if (registeredGames.has(id)) {
      return respond(409, "이미 내 게임으로 등록된 게임입니다.")
    }
    registeredGames.add(id)
    return respond(200, "성공했습니다.")
  }),
]
