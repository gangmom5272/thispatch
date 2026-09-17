import { queryOptions, useQuery } from "@tanstack/react-query"
import { getLanguageAnalysis, getLanguageAnalysisDetail } from "../../api/languageAnalysis"

export const languageAnalysisKeys = {
  all: ["language-analysis"] as const,
  overview: (gameId: number) => [...languageAnalysisKeys.all, "overview", gameId] as const,
  detail: (gameId: number, languageCode: string) =>
    [...languageAnalysisKeys.all, "detail", gameId, languageCode] as const,
}

export const languageAnalysisOptions = (gameId: number) =>
  queryOptions({
    queryKey: languageAnalysisKeys.overview(gameId),
    queryFn: ({ signal }) => getLanguageAnalysis(gameId, signal),
  })

export function useLanguageAnalysis(gameId: number) {
  return useQuery(languageAnalysisOptions(gameId))
}

export const languageAnalysisDetailOptions = (gameId: number, languageCode: string) =>
  queryOptions({
    queryKey: languageAnalysisKeys.detail(gameId, languageCode),
    queryFn: ({ signal }) => getLanguageAnalysisDetail(gameId, languageCode, signal),
  })

export function useLanguageAnalysisDetail(gameId: number, languageCode: string) {
  return useQuery(languageAnalysisDetailOptions(gameId, languageCode))
}
