import { http, HttpResponse } from "msw"

interface MockUser {
  id: number
  email: string
  password: string
  nickname: string
}

const users: MockUser[] = [
  {
    id: 1,
    email: "user@example.com",
    password: "password",
    nickname: "황용진",
  },
]

const accessToUserId = new Map<string, number>()
const refreshToUserId = new Map<string, number>()

function nowSeoul() {
  return new Date().toLocaleString("sv-SE", { timeZone: "Asia/Seoul" }).replace("T", " ")
}

function okEnvelope<T>(data: T, message = "성공했습니다.") {
  return {
    code: "200",
    message,
    responsedAt: nowSeoul(),
    data,
    success: true as const,
  }
}

function okMessage(message: string) {
  return {
    code: "200",
    message,
    responsedAt: nowSeoul(),
    success: true as const,
  }
}

function errorBody(code: string, message: string) {
  return {
    code,
    message,
    responsedAt: nowSeoul(),
  }
}

function issueTokens(userId: number) {
  const accessToken = `access-${userId}-${crypto.randomUUID()}`
  const refreshToken = `refresh-${userId}-${crypto.randomUUID()}`
  accessToUserId.set(accessToken, userId)
  refreshToUserId.set(refreshToken, userId)
  return { accessToken, refreshToken }
}

function userFromAuthHeader(request: Request) {
  const header = request.headers.get("Authorization")
  if (!header?.startsWith("Bearer ")) return null
  const token = header.slice("Bearer ".length)
  const userId = accessToUserId.get(token)
  if (userId === undefined) return null
  return users.find((user) => user.id === userId) ?? null
}

export const handlers = [
  http.get("/api/health", () => {
    return HttpResponse.json({ status: "ok" })
  }),

  http.get("/api/session", ({ request }) => {
    const user = userFromAuthHeader(request)
    if (!user) {
      return HttpResponse.json(okEnvelope({ authenticated: false, user: null }))
    }
    return HttpResponse.json(
      okEnvelope({
        authenticated: true,
        user: { id: user.id, nickname: user.nickname },
      }),
    )
  }),

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
