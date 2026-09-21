import type { CaseSearch, CaseSearchInput, PatchDetail, PlanStructure } from "../types"
import { api } from "./client"

/** GET /patches/{patchId}/translation 응답 data. */
export interface PatchTranslation {
  translatedTitle: string
  translatedBody: string
}

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

export function getPatch(
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
 * 패치노트 제목·본문 한국어 번역을 온디맨드로 조회한다.
 * GET /patches/{patchId}/translation — 원문은 body로 보내지 않는다.
 */
export function getPatchTranslation(
  patchId: string,
  signal?: AbortSignal,
): Promise<PatchTranslation> {
  return api.get<PatchTranslation>({
    path: `/patches/${patchId}/translation`,
    config: { signal },
  })
}
