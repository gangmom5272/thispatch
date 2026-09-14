import type { GameFilters, GameList } from "../types"
import { api } from "./client"

export function getGames(
  filters: GameFilters,
  cursor?: string,
  signal?: AbortSignal,
): Promise<GameList> {
  return api.get<GameList>({
    path: "/games",
    config: {
      params: { ...filters, genreIds: filters.genreIds?.join(",") || undefined, cursor },
      signal,
    },
  })
}

export function createMyGame(gameId: number): Promise<void> {
  return api.post<void>({ path: `/games/${gameId}/my-game` })
}

export function deleteMyGame(gameId: number): Promise<void> {
  return api.delete<void>({ path: `/games/${gameId}/my-game` })
}
