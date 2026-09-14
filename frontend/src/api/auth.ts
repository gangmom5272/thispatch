import { api } from "@/api/client"

export interface LoginRequest {
  email: string
  password: string
}

export interface SignupRequest {
  email: string
  password: string
  nickname: string
}

export interface AuthTokens {
  accessToken: string
  refreshToken: string
}

export interface RefreshTokenResult {
  accessToken: string
}

export function login(body: LoginRequest): Promise<AuthTokens> {
  return api.post<AuthTokens>({
    path: "/auth/login",
    body,
  })
}

export function signup(body: SignupRequest): Promise<void> {
  return api.post<void>({
    path: "/auth/signup",
    body,
  })
}

export function refreshToken(token: string): Promise<RefreshTokenResult> {
  return api.post<RefreshTokenResult>({
    path: "/auth/refresh",
    body: { refreshToken: token },
  })
}
