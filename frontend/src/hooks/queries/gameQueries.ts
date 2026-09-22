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
import { createMyGame, deleteMyGame, getGame, getGames, getMyGames } from "../../api/games"
import type { GameFilters } from "../../types"

const SUGGESTION_LIMIT = 5
const SUGGESTION_STALE_MS = 5 * 60_000
/** 무한 스크롤 한 페이지 크기 */
const GAME_PAGE_LIMIT = 12

export const gameKeys = {
  all: ["games"] as const,
  lists: () => [...gameKeys.all, "list"] as const,
  list: (filters: GameFilters) => [...gameKeys.lists(), filters] as const,
  myLists: () => [...gameKeys.all, "my-list"] as const,
  myList: (filters: GameFilters) => [...gameKeys.myLists(), filters] as const,
  details: () => [...gameKeys.all, "detail"] as const,
  detail: (gameId: number) => [...gameKeys.details(), gameId] as const,
  suggestions: (search: string) => [...gameKeys.all, "suggestions", search] as const,
}

export const gameListOptions = (filters: GameFilters) =>
  infiniteQueryOptions({
    queryKey: gameKeys.list(filters),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => getGames(filters, pageParam, signal),
    getNextPageParam: (lastPage) =>
      lastPage?.page?.hasNext ? (lastPage.page.nextCursor ?? undefined) : undefined,
  })

export function useGameList(filters: GameFilters) {
  return useInfiniteQuery(gameListOptions({ ...filters, limit: GAME_PAGE_LIMIT }))
}

export const myGameListOptions = (filters: GameFilters) =>
  infiniteQueryOptions({
    queryKey: gameKeys.myList(filters),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => getMyGames(filters, pageParam, signal),
    getNextPageParam: (lastPage) =>
      lastPage?.page?.hasNext ? (lastPage.page.nextCursor ?? undefined) : undefined,
  })

export function useMyGameList(filters: GameFilters) {
  return useInfiniteQuery(myGameListOptions({ ...filters, limit: GAME_PAGE_LIMIT }))
}

export const gameDetailOptions = (gameId: number) =>
  queryOptions({
    queryKey: gameKeys.detail(gameId),
    queryFn: ({ signal }) => getGame(gameId, signal),
  })

export function useGameDetail(gameId: number | null) {
  return useQuery({
    ...gameDetailOptions(gameId ?? 0),
    enabled: gameId !== null,
  })
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
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: gameKeys.lists() }),
        queryClient.invalidateQueries({ queryKey: gameKeys.myLists() }),
      ])
    },
  })
}
