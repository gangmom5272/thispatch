export interface MockUser {
  id: number
  email: string
  password: string
  nickname: string
}

export const users: MockUser[] = [
  {
    id: 1,
    email: "user@example.com",
    password: "password",
    nickname: "황용진",
  },
]

// ponytail: 토큰에 userId를 넣어 새로고침 후에도 유효하게 한다. 서버처럼 발급/폐기 추적이 필요하면 Map으로 교체.
export function issueAccessToken(userId: number) {
  return `access-${userId}-${crypto.randomUUID()}`
}

export function issueTokens(userId: number) {
  return {
    accessToken: issueAccessToken(userId),
    refreshToken: `refresh-${userId}-${crypto.randomUUID()}`,
  }
}

export function userIdFromToken(token: string, kind: "access" | "refresh") {
  const [prefix, id] = token.split("-")
  const userId = Number(id)
  return prefix === kind && Number.isSafeInteger(userId) ? userId : undefined
}

export function userFromAuthHeader(request: Request) {
  const header = request.headers.get("Authorization")
  if (!header?.startsWith("Bearer ")) return null
  const userId = userIdFromToken(header.slice("Bearer ".length), "access")
  if (userId === undefined) return null
  return users.find((user) => user.id === userId) ?? null
}
