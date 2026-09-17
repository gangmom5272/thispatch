import type {
  PatchDetail,
  PlaytimeTopics,
  PlaytimeTopicsAiSummary,
  ReactionTrends,
  ReactionTrendsAiSummary,
} from "@/types/statistics"
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
    config: { params: { startDate, endDate }, signal, timeout: 45_000 },
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

/**
 * 플레이타임 구간×토픽 집계를 조회한다.
 * @param bandNo 1~4면 해당 밴드, null이면 전체(ALL)
 */
export function getPlaytimeTopics(
  gameId: number,
  bandNo: number | null,
  signal?: AbortSignal,
): Promise<PlaytimeTopics> {
  // bandNo가 null이면 쿼리에서 생략 → 서버가 전체 구간으로 응답
  const params = bandNo === null ? undefined : { bandNo }
  return api.get<PlaytimeTopics>({
    path: `/games/${gameId}/playtime-topics`,
    config: { params, signal },
  })
}

/**
 * 선택 밴드에 대한 플레이타임×토픽 AI 요약을 조회한다.
 * @param bandNo 1~4 또는 null(전체)
 */
export function getPlaytimeTopicsSummary(
  gameId: number,
  bandNo: number | null,
  signal?: AbortSignal,
): Promise<PlaytimeTopicsAiSummary> {
  const params = bandNo === null ? undefined : { bandNo }
  return api.get<PlaytimeTopicsAiSummary>({
    path: `/games/${gameId}/summaries/playtime-topics`,
    config: { params, signal, timeout: 45_000 },
  })
}
