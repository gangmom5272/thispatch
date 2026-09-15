import type { PlanStructure } from "../types"
import { api } from "./client"

export function createPlanStructure(gameId: number, text: string): Promise<PlanStructure> {
  return api.post<PlanStructure>({ path: `/games/${gameId}/plan-structures`, body: { text } })
}
