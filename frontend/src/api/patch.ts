import type { CaseSearch, CaseSearchInput, PlanStructure } from "../types"
import { api } from "./client"

export function createPlanStructure(gameId: number, text: string): Promise<PlanStructure> {
  return api.post<PlanStructure>({ path: `/games/${gameId}/plan-structures`, body: { text } })
}

export function createCaseSearch(
  gameId: number,
  input: CaseSearchInput,
  signal?: AbortSignal,
): Promise<CaseSearch> {
  return api.post<CaseSearch>({
    path: `/games/${gameId}/case-searches`,
    body: input,
    config: { signal },
  })
}
