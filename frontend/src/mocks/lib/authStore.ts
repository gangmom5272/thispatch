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

export const accessToUserId = new Map<string, number>()
export const refreshToUserId = new Map<string, number>()

export function issueTokens(userId: number) {
  const accessToken = `access-${userId}-${crypto.randomUUID()}`
  const refreshToken = `refresh-${userId}-${crypto.randomUUID()}`
  accessToUserId.set(accessToken, userId)
  refreshToUserId.set(refreshToken, userId)
  return { accessToken, refreshToken }
}

export function userFromAuthHeader(request: Request) {
  const header = request.headers.get("Authorization")
  if (!header?.startsWith("Bearer ")) return null
  const token = header.slice("Bearer ".length)
  const userId = accessToUserId.get(token)
  if (userId === undefined) return null
  return users.find((user) => user.id === userId) ?? null
}
