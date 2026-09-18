import { api } from "@/api/client"

export interface UpdateNicknameRequest {
  nickname: string
}

export interface UpdateNicknameResult {
  nickname: string
}

export interface UpdatePasswordRequest {
  currentPassword: string
  newPassword: string
}

// TODO: 백엔드 미구현 — backend/docs/api/member.md에 닉네임 변경 API가 확정되면 경로·응답을 맞춘다.
export function updateNickname(body: UpdateNicknameRequest): Promise<UpdateNicknameResult> {
  return api.patch<UpdateNicknameResult>({ path: "/members/me/nickname", body })
}

// TODO: 백엔드 미구현 — backend/docs/api/member.md에 비밀번호 변경 API가 확정되면 경로·응답을 맞춘다.
export function updatePassword(body: UpdatePasswordRequest): Promise<void> {
  return api.patch<void>({ path: "/members/me/password", body })
}

/** DELETE /members/me — 현재 회원을 탈퇴 처리한다. */
export function withdrawMember(): Promise<void> {
  return api.delete<void>({ path: "/members/me" })
}
