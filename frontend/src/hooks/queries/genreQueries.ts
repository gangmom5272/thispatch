import { queryOptions, useQuery } from "@tanstack/react-query"
import { getGenres } from "../../api/genres"

const GENRE_STALE_MS = 5 * 60_000

export const genreKeys = {
  all: ["genres"] as const,
  list: () => [...genreKeys.all, "list"] as const,
}

export const genreListOptions = () =>
  queryOptions({
    queryKey: genreKeys.list(),
    queryFn: ({ signal }) => getGenres(signal),
    select: (list) => list.items,
    staleTime: GENRE_STALE_MS,
  })

export function useGenreList() {
  return useQuery(genreListOptions())
}
