import type {
  LanguageAnalysis,
  LanguageAnalysisDetail,
  LanguageAnalysisReviews,
  LanguageAnalysisSummary,
} from "../types"
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
    config: { signal, timeout: 45_000 },
  })
}

export function getLanguageAnalysisReviews(
  gameId: number,
  languageCode: string,
  signal?: AbortSignal,
): Promise<LanguageAnalysisReviews> {
  return api.get<LanguageAnalysisReviews>({
    path: `/games/${gameId}/language-analysis/${encodeURIComponent(languageCode)}/reviews`,
    config: { signal },
  })
}

export function getLanguageAnalysisSummary(
  gameId: number,
  languageCode: string,
  signal?: AbortSignal,
): Promise<LanguageAnalysisSummary> {
  return api.get<LanguageAnalysisSummary>({
    path: `/games/${gameId}/language-analysis/${encodeURIComponent(languageCode)}/summary`,
    config: { signal, timeout: 45_000 },
  })
}
