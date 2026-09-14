import { api } from "@/api/client"

export interface SessionUser {
  id: number
  nickname: string
}

export interface SessionData {
  authenticated: boolean
  user: SessionUser | null
}

export function getSession(signal?: AbortSignal): Promise<SessionData> {
  return api.get<SessionData>({
    path: "/session",
    config: { signal },
  })
}
