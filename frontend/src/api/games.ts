import type { GameDetail, GameFilters, GameList, MyGameList } from "../types"
import { isApiError } from "./error"
import { api } from "./client"

function listParams(filters: GameFilters, cursor?: string) {
  return { ...filters, genreIds: filters.genreIds?.join(",") || undefined, cursor }
}

/** 목록 응답의 공통 뼈대 — GameList / MyGameList 모두 수용한다. */
type ListPayload = {
  items: readonly unknown[]
  page: GameList["page"]
}

/** 결과 0건·부분 응답을 안전한 빈 목록으로 맞춘다. */
function emptyGameList(limit = 0): ListPayload {
  return {
    items: [],
    page: {
      limit,
      nextCursor: null,
      hasNext: false,
      totalCount: 0,
    },
  }
}

/**
 * 목록 API data가 null/불완전해도 페이지네이션이 깨지지 않게 정규화한다.
 * 빈 검색을 빈 본문(null)으로 주는 백엔드와도 호환한다.
 */
function normalizeGameList(data: ListPayload | null | undefined, limit = 0): ListPayload {
  if (!data || typeof data !== "object") {
    return emptyGameList(limit)
  }

  const items = Array.isArray(data.items) ? data.items : []
  const page = data.page && typeof data.page === "object" ? data.page : null

  return {
    items,
    page: {
      limit: typeof page?.limit === "number" ? page.limit : limit,
      nextCursor: page?.nextCursor ?? null,
      hasNext: Boolean(page?.hasNext),
      totalCount: typeof page?.totalCount === "number" ? page.totalCount : items.length,
    },
  }
}

/** 200이지만 본문이 비어 envelope unwrap이 실패한 경우를 빈 목록으로 완화한다. */
async function getListOrEmpty<T extends ListPayload>(
  fetch: () => Promise<T | null | undefined>,
  limit = 0,
): Promise<T> {
  try {
    return normalizeGameList(await fetch(), limit) as T
  } catch (error) {
    if (
      isApiError(error) &&
      error.status === 200 &&
      error.message === "응답 형식이 올바르지 않습니다."
    ) {
      return emptyGameList(limit) as T
    }
    throw error
  }
}

export function getGames(
  filters: GameFilters,
  cursor?: string,
  signal?: AbortSignal,
): Promise<GameList> {
  return getListOrEmpty(
    () =>
      api.get<GameList | null | undefined>({
        path: "/games",
        config: { params: listParams(filters, cursor), signal },
      }),
    filters.limit ?? 0,
  )
}

export function getMyGames(filters: GameFilters, signal?: AbortSignal): Promise<MyGameList> {
  return getListOrEmpty(
    () =>
      api.get<MyGameList | null | undefined>({
        path: "/members/me/games",
        config: { params: listParams(filters), signal },
      }),
    filters.limit ?? 0,
  )
}

export function getGame(gameId: number, signal?: AbortSignal): Promise<GameDetail> {
  return api.get<GameDetail>({
    path: `/games/${gameId}`,
    config: { signal },
  })
}

export function createMyGame(gameId: number): Promise<void> {
  return api.post<void>({ path: `/games/${gameId}/my-game` })
}

export function deleteMyGame(gameId: number): Promise<void> {
  return api.delete<void>({ path: `/games/${gameId}/my-game` })
}
