import { mutationOptions, queryOptions, useMutation, useQuery } from "@tanstack/react-query"
import { createCaseSearch, createPlanStructure } from "../../api/patch"
import type { CaseSearchInput } from "../../types"

interface CreatePlanStructureVariables {
  gameId: number
  text: string
}

export const patchKeys = {
  all: ["patch"] as const,
  caseSearch: (gameId: number, input: CaseSearchInput) =>
    [...patchKeys.all, "case-search", gameId, input] as const,
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
