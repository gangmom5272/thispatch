import {
  infiniteQueryOptions,
  keepPreviousData,
  mutationOptions,
  queryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { createMyGame, deleteMyGame, getGames } from "../../api/games"
import type { GameFilters } from "../../types"

const SUGGESTION_LIMIT = 5
const SUGGESTION_STALE_MS = 5 * 60_000

export const gameKeys = {
  all: ["games"] as const,
  lists: () => [...gameKeys.all, "list"] as const,
  list: (filters: GameFilters) => [...gameKeys.lists(), filters] as const,
  suggestions: (search: string) => [...gameKeys.all, "suggestions", search] as const,
}

export const gameListOptions = (filters: GameFilters) =>
  infiniteQueryOptions({
    queryKey: gameKeys.list(filters),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => getGames(filters, pageParam, signal),
    getNextPageParam: (lastPage) =>
      lastPage.page.hasNext ? (lastPage.page.nextCursor ?? undefined) : undefined,
  })

export function useGameList(filters: GameFilters) {
  return useInfiniteQuery(gameListOptions(filters))
}

export const gameSuggestionOptions = (search: string) =>
  queryOptions({
    queryKey: gameKeys.suggestions(search),
    queryFn: ({ signal }) => getGames({ search, limit: SUGGESTION_LIMIT }, undefined, signal),
    select: (list) => list.items,
    staleTime: SUGGESTION_STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  })

export function useGameSuggestions(search: string, enabled: boolean) {
  return useQuery({ ...gameSuggestionOptions(search), enabled })
}

interface ToggleMyGameVariables {
  gameId: number
  isMine: boolean
}

export const toggleMyGameOptions = () =>
  mutationOptions({
    mutationFn: ({ gameId, isMine }: ToggleMyGameVariables) =>
      isMine ? deleteMyGame(gameId) : createMyGame(gameId),
  })

export function useToggleMyGame() {
  const queryClient = useQueryClient()
  return useMutation({
    ...toggleMyGameOptions(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: gameKeys.lists() }),
  })
}
