import { queryOptions, useQuery } from "@tanstack/react-query"
import {
  getLanguageAnalysis,
  getLanguageAnalysisDetail,
  getLanguageAnalysisReviews,
  getLanguageAnalysisSummary,
} from "../../api/languageAnalysis"

export const languageAnalysisKeys = {
  all: ["language-analysis"] as const,
  overview: (gameId: number) => [...languageAnalysisKeys.all, "overview", gameId] as const,
  detail: (gameId: number, languageCode: string) =>
    [...languageAnalysisKeys.all, "detail", gameId, languageCode] as const,
  reviews: (gameId: number, languageCode: string) =>
    [...languageAnalysisKeys.all, "reviews", gameId, languageCode] as const,
  summary: (gameId: number, languageCode: string) =>
    [...languageAnalysisKeys.all, "summary", gameId, languageCode] as const,
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

export const languageAnalysisReviewsOptions = (gameId: number, languageCode: string) =>
  queryOptions({
    queryKey: languageAnalysisKeys.reviews(gameId, languageCode),
    queryFn: ({ signal }) => getLanguageAnalysisReviews(gameId, languageCode, signal),
  })

export function useLanguageAnalysisReviews(gameId: number, languageCode: string) {
  return useQuery(languageAnalysisReviewsOptions(gameId, languageCode))
}

export const languageAnalysisSummaryOptions = (gameId: number, languageCode: string) =>
  queryOptions({
    queryKey: languageAnalysisKeys.summary(gameId, languageCode),
    queryFn: ({ signal }) => getLanguageAnalysisSummary(gameId, languageCode, signal),
  })

export function useLanguageAnalysisSummary(gameId: number, languageCode: string) {
  return useQuery(languageAnalysisSummaryOptions(gameId, languageCode))
}
