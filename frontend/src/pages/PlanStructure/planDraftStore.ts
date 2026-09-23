import { create } from "zustand"
import type { PlanSlot, PlanStructure } from "@/types"

interface PlanDraft {
  text: string
  excludedGenreIds: number[]
  structure: PlanStructure | null
  editedSlots: PlanSlot[] | null
}

const EMPTY_DRAFT: PlanDraft = {
  text: "",
  excludedGenreIds: [],
  structure: null,
  editedSlots: null,
}

interface PlanDraftState {
  drafts: Record<number, PlanDraft>
  updateDraft: (gameId: number, changes: Partial<PlanDraft>) => void
}

const usePlanDraftStore = create<PlanDraftState>()((set) => ({
  drafts: {},
  updateDraft: (gameId, changes) =>
    set((state) => ({
      drafts: {
        ...state.drafts,
        [gameId]: { ...(state.drafts[gameId] ?? EMPTY_DRAFT), ...changes },
      },
    })),
}))

/** 유사 사례 검색 화면을 오간 뒤에도 남아 있어야 하는 기획안 입력값. */
export function usePlanDraft(gameId: number): PlanDraft {
  return usePlanDraftStore((state) => state.drafts[gameId] ?? EMPTY_DRAFT)
}

export function useUpdatePlanDraft(): PlanDraftState["updateDraft"] {
  return usePlanDraftStore((state) => state.updateDraft)
}
