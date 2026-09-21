import {
  infiniteQueryOptions,
  mutationOptions,
  queryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
} from "@tanstack/react-query"
import {
  createCaseSearch,
  createPlanStructure,
  getPatch,
  getPatchPlan,
  getPatchPlans,
} from "../../api/patch"
import type { CaseSearchInput } from "../../types"

const PLAN_HISTORY_LIMIT = 20

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
  plans: () => [...patchKeys.all, "plans"] as const,
  planList: (limit: number) => [...patchKeys.plans(), "list", limit] as const,
  planDetail: (planId: number) => [...patchKeys.plans(), "detail", planId] as const,
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
      input ?? { planId: 0, confirmedSlots: [], genreIds: [], sort: "REVIEW_COUNT_DESC" },
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

export const patchPlanListOptions = (limit: number) =>
  infiniteQueryOptions({
    queryKey: patchKeys.planList(limit),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => getPatchPlans(limit, pageParam, signal),
    getNextPageParam: (lastPage) =>
      lastPage?.page?.hasNext ? (lastPage.page.nextCursor ?? undefined) : undefined,
  })

export function usePatchPlanList() {
  return useInfiniteQuery(patchPlanListOptions(PLAN_HISTORY_LIMIT))
}

export const patchPlanDetailOptions = (planId: number) =>
  queryOptions({
    queryKey: patchKeys.planDetail(planId),
    queryFn: ({ signal }) => getPatchPlan(planId, signal),
  })

export function usePatchPlanDetail(planId: number | null) {
  return useQuery({ ...patchPlanDetailOptions(planId ?? 0), enabled: planId !== null })
}
