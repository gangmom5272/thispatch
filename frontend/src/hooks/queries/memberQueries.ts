import { mutationOptions, useMutation, useQueryClient } from "@tanstack/react-query"
import {
  updateNickname,
  updatePassword,
  withdrawMember,
  type UpdateNicknameRequest,
  type UpdatePasswordRequest,
} from "@/api/member"
import { sessionKeys } from "@/hooks/queries/sessionQueries"

export const updateNicknameMutationOptions = mutationOptions({
  mutationFn: (body: UpdateNicknameRequest) => updateNickname(body),
})

export const updatePasswordMutationOptions = mutationOptions({
  mutationFn: (body: UpdatePasswordRequest) => updatePassword(body),
})

export const withdrawMemberMutationOptions = mutationOptions({
  mutationFn: () => withdrawMember(),
})

export function useUpdateNickname() {
  const queryClient = useQueryClient()

  return useMutation({
    ...updateNicknameMutationOptions,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
  })
}

export function useUpdatePassword() {
  return useMutation(updatePasswordMutationOptions)
}

/** 탈퇴 후 로그아웃 처리(토큰·세션 캐시 정리)는 호출부에서 useLogout으로 이어 간다. */
export function useWithdrawMember() {
  return useMutation(withdrawMemberMutationOptions)
}
