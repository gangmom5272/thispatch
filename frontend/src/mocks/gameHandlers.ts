import { delay, http, HttpResponse } from "msw"
import {
  DEFAULT_GAME_SORT,
  GAME_RANGE_FILTERS,
  isGameSort,
  type GameRangeKey,
} from "../constants/games"
import type { Game, GameDetail, GameSort, MyGame } from "../types"
import { mockGames } from "./games"
import { userFromAuthHeader } from "./lib/authStore"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")
const registeredGames = new Set(mockGames.filter((game) => game.isMine).map((game) => game.id))
const invalidFiltersMessage = "검색 조건 또는 페이지 커서가 올바르지 않습니다."

type MockGame = (typeof mockGames)[number]

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

function toGameDetail(game: MockGame): GameDetail {
  return {
    id: game.id,
    capsuleImageUrl: game.capsuleImageUrl,
    title: game.title,
    tags: game.tags,
    positiveRate: game.positiveRate,
    isMine: registeredGames.has(game.id),
    description: game.gameSummary.description,
    releasedOn: game.releasedAt,
    reviewCount: game.reviewCount,
    lastCollectedAt: "2026-09-07T04:10:00Z",
  }
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

const comparators: Record<GameSort, (a: MockGame, b: MockGame) => number> = {
  POSITIVE_RATE_ASC: (a, b) => a.positiveRate - b.positiveRate,
  REVIEW_COUNT_DESC: (a, b) => b.reviewCount - a.reviewCount,
  REACTION_CHANGE_DESC: (a, b) => b.reactionChange - a.reactionChange,
  RELEASE_DATE_DESC: (a, b) => b.releasedAt.localeCompare(a.releasedAt),
}

/** 전체 게임과 내 게임이 같은 검색·정렬·커서 규칙을 쓰되 커서는 scope 별로 분리된다. */
function listResponse(request: Request, scope: "all" | "mine", pool: MockGame[]) {
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

  const developer = (params.get("developer") ?? "").trim().toLowerCase()
  if (developer.length > 500) {
    return respond(400, invalidFiltersMessage)
  }
  const ranges: Partial<Record<GameRangeKey, number>> = {}
  for (const group of GAME_RANGE_FILTERS) {
    for (const key of [group.from, group.to]) {
      const raw = params.get(key)
      if (raw === null) continue
      const value = Number(raw)
      if (!/^\d+$/.test(raw) || value < group.min || value > group.max) {
        return respond(400, invalidFiltersMessage)
      }
      ranges[key] = value
    }
    const from = ranges[group.from]
    const to = ranges[group.to]
    if (from !== undefined && to !== undefined && from > to) {
      return respond(400, "입력값을 확인해주세요.")
    }
  }
  const inRange = (value: number, from: number | undefined, to: number | undefined) =>
    (from === undefined || value >= from) && (to === undefined || value <= to)

  const matches = pool
    .filter(
      (game) =>
        game.title.toLowerCase().includes(search.trim().toLowerCase()) &&
        (genreIds.length === 0 || game.tags.some((tag) => genreIds.includes(tag.id))) &&
        inRange(
          Number(game.releasedAt.slice(0, 4)),
          ranges.releaseYearFrom,
          ranges.releaseYearTo,
        ) &&
        inRange(game.reviewCount, ranges.minReviewCount, ranges.maxReviewCount) &&
        inRange(game.positiveRate, ranges.minPositiveRate, ranges.maxPositiveRate) &&
        game.gameSummary.developer.toLowerCase().includes(developer),
    )
    .toSorted((a, b) => comparators[sort](a, b) || a.id - b.id)

  const filterKey = encodeURIComponent(
    JSON.stringify([scope, search, sort, genreIds.toSorted((a, b) => a - b), ranges, developer]),
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

  const items: Array<Game | MyGame> = matches.slice(offset, offset + limit).map((game) => ({
    id: game.id,
    capsuleImageUrl: game.capsuleImageUrl,
    title: game.title,
    tags: game.tags,
    positiveRate: game.positiveRate,
    ...(scope === "all" ? { isMine: registeredGames.has(game.id) } : {}),
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
      nextCursor: hasNext && lastItem ? btoa(JSON.stringify({ id: lastItem.id, filterKey })) : null,
    },
  })
}

export const gameHandlers = [
  http.get(`${baseURL}/games`, async ({ request }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    return listResponse(request, "all", mockGames)
  }),
  http.get(`${baseURL}/members/me/games`, async ({ request }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    return listResponse(
      request,
      "mine",
      mockGames.filter((game) => registeredGames.has(game.id)),
    )
  }),
  http.get(`${baseURL}/games/:gameId`, async ({ request, params }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const id = Number(params.gameId)
    if (!Number.isSafeInteger(id) || id < 1) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    const game = mockGames.find((item) => item.id === id)
    if (!game) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    return respond(200, "성공했습니다.", toGameDetail(game))
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
