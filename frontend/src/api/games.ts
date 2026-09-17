import type { GameDetail, GameFilters, GameList, MyGameList } from "../types"
import { api } from "./client"

function listParams(filters: GameFilters, cursor?: string) {
  return { ...filters, genreIds: filters.genreIds?.join(",") || undefined, cursor }
}

export function getGames(
  filters: GameFilters,
  cursor?: string,
  signal?: AbortSignal,
): Promise<GameList> {
  return api.get<GameList>({
    path: "/games",
    config: { params: listParams(filters, cursor), signal },
  })
}

export function getMyGames(filters: GameFilters, signal?: AbortSignal): Promise<MyGameList> {
  return api.get<MyGameList>({
    path: "/members/me/games",
    config: { params: listParams(filters), signal },
  })
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
