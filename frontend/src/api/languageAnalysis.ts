import type { LanguageAnalysis, LanguageAnalysisDetail } from "../types"
import { api } from "./client"

export function getLanguageAnalysis(
  gameId: number,
  signal?: AbortSignal,
): Promise<LanguageAnalysis> {
  return api.get<LanguageAnalysis>({
    path: `/games/${gameId}/language-analysis`,
    config: { signal },
  })
}

export function getLanguageAnalysisDetail(
  gameId: number,
  languageCode: string,
  signal?: AbortSignal,
): Promise<LanguageAnalysisDetail> {
  return api.get<LanguageAnalysisDetail>({
    path: `/games/${gameId}/language-analysis/${encodeURIComponent(languageCode)}`,
    config: { signal },
  })
}
