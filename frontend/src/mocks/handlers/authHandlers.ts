import { http, HttpResponse } from "msw"
import { accessToUserId, issueTokens, refreshToUserId, users } from "@/mocks/lib/authStore"
import { errorBody, okEnvelope, okMessage } from "@/mocks/lib/envelope"

export const authHandlers = [
  http.post("/api/auth/login", async ({ request }) => {
    const body = (await request.json()) as { email?: string; password?: string }
    if (!body.email || !body.password) {
      return HttpResponse.json(errorBody("VALIDATION_FAILED", "입력값을 확인해주세요."), {
        status: 400,
      })
    }

    const user = users.find((item) => item.email === body.email)
    if (!user || user.password !== body.password) {
      return HttpResponse.json(errorBody("401", "이메일 또는 비밀번호가 일치하지 않습니다."), {
        status: 401,
      })
    }

    return HttpResponse.json(okEnvelope(issueTokens(user.id)))
  }),

  http.post("/api/auth/signup", async ({ request }) => {
    const body = (await request.json()) as {
      email?: string
      password?: string
      nickname?: string
    }

    if (!body.email || !body.password || !body.nickname) {
      return HttpResponse.json(errorBody("VALIDATION_FAILED", "입력값을 확인해주세요."), {
        status: 400,
      })
    }

    if (users.some((user) => user.email === body.email)) {
      return HttpResponse.json(errorBody("409", "이미 가입된 이메일입니다."), {
        status: 409,
      })
    }

    users.push({
      id: users.length + 1,
      email: body.email,
      password: body.password,
      nickname: body.nickname,
    })

    return HttpResponse.json(okMessage("회원가입에 성공했습니다."))
  }),

  http.post("/api/auth/refresh", async ({ request }) => {
    const body = (await request.json()) as { refreshToken?: string }
    if (!body.refreshToken) {
      return HttpResponse.json(errorBody("400", "Refresh Token이 누락되었습니다."), {
        status: 400,
      })
    }

    const userId = refreshToUserId.get(body.refreshToken)
    if (userId === undefined) {
      return HttpResponse.json(errorBody("401", "Refresh Token이 유효하지 않습니다."), {
        status: 401,
      })
    }

    const accessToken = `access-${userId}-${crypto.randomUUID()}`
    accessToUserId.set(accessToken, userId)
    return HttpResponse.json(okEnvelope({ accessToken }, "토큰 재발급에 성공했습니다."))
  }),
]
