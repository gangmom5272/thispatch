import { mutationOptions, useMutation } from "@tanstack/react-query"
import { createPlanStructure } from "../../api/patch"

interface CreatePlanStructureVariables {
  gameId: number
  text: string
}

export const createPlanStructureOptions = () =>
  mutationOptions({
    mutationFn: ({ gameId, text }: CreatePlanStructureVariables) =>
      createPlanStructure(gameId, text),
  })

export function useCreatePlanStructure() {
  return useMutation(createPlanStructureOptions())
}
