import { keepPreviousData, queryOptions, useQuery } from "@tanstack/react-query"
import {
  getPatchDetail,
  getPlaytimeTopics,
  getPlaytimeTopicsSummary,
  getReactionTrends,
  getReactionTrendsSummary,
} from "@/api/statistics"

export const statisticsKeys = {
  all: ["statistics"] as const,
  reactionTrends: (gameId: number, startDate: string, endDate: string) =>
    [...statisticsKeys.all, "reaction-trends", gameId, startDate, endDate] as const,
  reactionTrendsSummary: (gameId: number, startDate: string, endDate: string) =>
    [...statisticsKeys.all, "reaction-trends-summary", gameId, startDate, endDate] as const,
  patch: (gameId: number, patchId: string) =>
    [...statisticsKeys.all, "patch", gameId, patchId] as const,
  /** bandKey: "all" | "1" | "2" | "3" | "4" */
  playtimeTopics: (gameId: number, bandKey: string) =>
    [...statisticsKeys.all, "playtime-topics", gameId, bandKey] as const,
  playtimeTopicsSummary: (gameId: number, bandKey: string) =>
    [...statisticsKeys.all, "playtime-topics-summary", gameId, bandKey] as const,
}

export const reactionTrendsOptions = (gameId: number, startDate: string, endDate: string) =>
  queryOptions({
    queryKey: statisticsKeys.reactionTrends(gameId, startDate, endDate),
    queryFn: ({ signal }) => getReactionTrends(gameId, startDate, endDate, signal),
  })

export function useReactionTrends(
  gameId: number | null,
  startDate: string | null,
  endDate: string | null,
) {
  return useQuery({
    ...reactionTrendsOptions(gameId ?? 0, startDate ?? "", endDate ?? ""),
    enabled: gameId !== null && Boolean(startDate) && Boolean(endDate),
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

/** bandNo를 쿼리 키 문자열로 정규화한다. */
function bandKeyOf(bandNo: number | null): string {
  return bandNo === null ? "all" : String(bandNo)
}

/** 플레이타임×토픽 집계 쿼리 옵션. */
export const playtimeTopicsOptions = (gameId: number, bandNo: number | null) =>
  queryOptions({
    queryKey: statisticsKeys.playtimeTopics(gameId, bandKeyOf(bandNo)),
    queryFn: ({ signal }) => getPlaytimeTopics(gameId, bandNo, signal),
  })

/**
 * 선택 밴드의 플레이타임×토픽 집계를 구독한다.
 * @param bandNo 1~4 또는 null(전체)
 */
export function usePlaytimeTopics(gameId: number | null, bandNo: number | null) {
  return useQuery({
    ...playtimeTopicsOptions(gameId ?? 0, bandNo),
    enabled: gameId !== null,
    placeholderData: keepPreviousData,
  })
}

/** 플레이타임×토픽 AI 요약 쿼리 옵션. */
export const playtimeTopicsSummaryOptions = (gameId: number, bandNo: number | null) =>
  queryOptions({
    queryKey: statisticsKeys.playtimeTopicsSummary(gameId, bandKeyOf(bandNo)),
    queryFn: ({ signal }) => getPlaytimeTopicsSummary(gameId, bandNo, signal),
    staleTime: 5 * 60_000,
  })

/**
 * 선택 밴드 AI 요약을 구독한다. enabled=false면 요청하지 않는다.
 */
export function usePlaytimeTopicsSummary(
  gameId: number | null,
  bandNo: number | null,
  enabled: boolean,
) {
  return useQuery({
    ...playtimeTopicsSummaryOptions(gameId ?? 0, bandNo),
    enabled: enabled && gameId !== null,
  })
}
