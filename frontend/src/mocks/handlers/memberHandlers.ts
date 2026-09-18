import { http, HttpResponse } from "msw"
import { userFromAuthHeader, users } from "@/mocks/lib/authStore"
import { errorBody, okEnvelope, okMessage } from "@/mocks/lib/envelope"

const NICKNAME_MAX_LENGTH = 50

function unauthorized() {
  return HttpResponse.json(errorBody("UNAUTHORIZED", "인증이 필요합니다."), { status: 401 })
}

function validationFailed() {
  return HttpResponse.json(errorBody("VALIDATION_FAILED", "입력값을 확인해주세요."), {
    status: 400,
  })
}

export const memberHandlers = [
  http.patch("/api/members/me/nickname", async ({ request }) => {
    const user = userFromAuthHeader(request)
    if (!user) return unauthorized()

    const body = (await request.json()) as { nickname?: string }
    const nickname = body.nickname ?? ""
    if (!nickname.trim() || [...nickname].length > NICKNAME_MAX_LENGTH) {
      return validationFailed()
    }

    user.nickname = nickname
    return HttpResponse.json(okEnvelope({ nickname }, "닉네임이 변경되었습니다."))
  }),

  http.patch("/api/members/me/password", async ({ request }) => {
    const user = userFromAuthHeader(request)
    if (!user) return unauthorized()

    const body = (await request.json()) as { currentPassword?: string; newPassword?: string }
    if (!body.currentPassword || !body.newPassword?.trim()) {
      return validationFailed()
    }
    if (user.password !== body.currentPassword) {
      return HttpResponse.json(
        errorBody("PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."),
        { status: 400 },
      )
    }

    user.password = body.newPassword
    return HttpResponse.json(okMessage("비밀번호가 변경되었습니다."))
  }),

  http.delete("/api/members/me", ({ request }) => {
    const user = userFromAuthHeader(request)
    if (!user) return unauthorized()

    users.splice(users.indexOf(user), 1)
    return HttpResponse.json(okMessage("회원탈퇴가 완료되었습니다."))
  }),
]
