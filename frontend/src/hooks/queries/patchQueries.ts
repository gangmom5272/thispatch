import { mutationOptions, queryOptions, useMutation, useQuery } from "@tanstack/react-query"
import {
  createCaseSearch,
  createPlanStructure,
  getPatch,
  getPatchTranslation,
} from "../../api/patch"
import type { CaseSearchInput } from "../../types"

interface CreatePlanStructureVariables {
  gameId: number
  text: string
}

export const patchKeys = {
  all: ["patch"] as const,
  caseSearch: (gameId: number, input: CaseSearchInput) =>
    [...patchKeys.all, "case-search", gameId, input] as const,
  detail: (gameId: number, patchId: string) =>
    [...patchKeys.all, "detail", gameId, patchId] as const,
  translation: (patchId: string) => [...patchKeys.all, "translation", patchId] as const,
}

export const createPlanStructureOptions = () =>
  mutationOptions({
    mutationFn: ({ gameId, text }: CreatePlanStructureVariables) =>
      createPlanStructure(gameId, text),
  })

export function useCreatePlanStructure() {
  return useMutation(createPlanStructureOptions())
}

export const caseSearchOptions = (gameId: number, input: CaseSearchInput) =>
  queryOptions({
    queryKey: patchKeys.caseSearch(gameId, input),
    queryFn: ({ signal }) => createCaseSearch(gameId, input, signal),
  })

export function useCaseSearch(gameId: number, input: CaseSearchInput | null) {
  return useQuery({
    ...caseSearchOptions(
      gameId,
      input ?? { confirmedSlots: [], genreIds: [], sort: "REVIEW_COUNT_DESC" },
    ),
    enabled: input !== null,
  })
}

export const patchDetailOptions = (gameId: number, patchId: string) =>
  queryOptions({
    queryKey: patchKeys.detail(gameId, patchId),
    queryFn: ({ signal }) => getPatch(gameId, patchId, signal),
  })

export function usePatchDetail(gameId: number, patchId: string) {
  return useQuery(patchDetailOptions(gameId, patchId))
}

/** 패치노트 번역 쿼리 옵션. */
export const patchTranslationOptions = (patchId: string) =>
  queryOptions({
    queryKey: patchKeys.translation(patchId),
    queryFn: ({ signal }) => getPatchTranslation(patchId, signal),
    staleTime: Infinity,
  })

/**
 * 패치노트 번역을 구독한다. 「번역」 토글이 켜진 뒤에만 조회한다.
 * @param enabled false면 네트워크 요청을 하지 않는다
 */
export function usePatchTranslation(patchId: string, enabled: boolean) {
  return useQuery({
    ...patchTranslationOptions(patchId),
    enabled: enabled && patchId.length > 0,
  })
}
