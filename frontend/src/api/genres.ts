import type { GenreList } from "../types"
import { api } from "./client"

export function getGenres(signal?: AbortSignal): Promise<GenreList> {
  return api.get<GenreList>({ path: "/genres", config: { signal } })
}
