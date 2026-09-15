import type { PatchDetail, ReactionTrends, ReactionTrendsAiSummary } from "@/types/statistics"
import { api } from "@/api/client"

export function getReactionTrends(
  gameId: number,
  startDate: string,
  signal?: AbortSignal,
): Promise<ReactionTrends> {
  return api.get<ReactionTrends>({
    path: `/games/${gameId}/reaction-trends`,
    config: { params: { startDate }, signal },
  })
}

export function getReactionTrendsSummary(
  gameId: number,
  startDate: string,
  endDate: string,
  signal?: AbortSignal,
): Promise<ReactionTrendsAiSummary> {
  return api.get<ReactionTrendsAiSummary>({
    path: `/games/${gameId}/summaries/reaction-trends`,
    config: { params: { startDate, endDate }, signal },
  })
}

export function getPatchDetail(
  gameId: number,
  patchId: string,
  signal?: AbortSignal,
): Promise<PatchDetail> {
  return api.get<PatchDetail>({
    path: `/games/${gameId}/patches/${patchId}`,
    config: { signal },
  })
}
