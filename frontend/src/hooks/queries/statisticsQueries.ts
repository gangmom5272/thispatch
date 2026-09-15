import { keepPreviousData, queryOptions, useQuery } from "@tanstack/react-query"
import { getPatchDetail, getReactionTrends, getReactionTrendsSummary } from "@/api/statistics"

export const statisticsKeys = {
  all: ["statistics"] as const,
  reactionTrends: (gameId: number, startDate: string) =>
    [...statisticsKeys.all, "reaction-trends", gameId, startDate] as const,
  reactionTrendsSummary: (gameId: number, startDate: string, endDate: string) =>
    [...statisticsKeys.all, "reaction-trends-summary", gameId, startDate, endDate] as const,
  patch: (gameId: number, patchId: string) =>
    [...statisticsKeys.all, "patch", gameId, patchId] as const,
}

export const reactionTrendsOptions = (gameId: number, startDate: string) =>
  queryOptions({
    queryKey: statisticsKeys.reactionTrends(gameId, startDate),
    queryFn: ({ signal }) => getReactionTrends(gameId, startDate, signal),
  })

export function useReactionTrends(gameId: number | null, startDate: string | null) {
  return useQuery({
    ...reactionTrendsOptions(gameId ?? 0, startDate ?? ""),
    enabled: gameId !== null && Boolean(startDate),
    placeholderData: keepPreviousData,
  })
}

export const reactionTrendsSummaryOptions = (gameId: number, startDate: string, endDate: string) =>
  queryOptions({
    queryKey: statisticsKeys.reactionTrendsSummary(gameId, startDate, endDate),
    queryFn: ({ signal }) => getReactionTrendsSummary(gameId, startDate, endDate, signal),
    staleTime: 5 * 60_000,
  })

export function useReactionTrendsSummary(
  gameId: number | null,
  startDate: string | null,
  endDate: string | null,
  enabled: boolean,
) {
  return useQuery({
    ...reactionTrendsSummaryOptions(gameId ?? 0, startDate ?? "", endDate ?? ""),
    enabled: enabled && gameId !== null && Boolean(startDate) && Boolean(endDate),
  })
}

export const patchDetailOptions = (gameId: number, patchId: string) =>
  queryOptions({
    queryKey: statisticsKeys.patch(gameId, patchId),
    queryFn: ({ signal }) => getPatchDetail(gameId, patchId, signal),
  })

export function usePatchDetail(gameId: number | null, patchId: string | null) {
  return useQuery({
    ...patchDetailOptions(gameId ?? 0, patchId ?? ""),
    enabled: gameId !== null && Boolean(patchId),
  })
}
