import type { AxiosRequestConfig, InternalAxiosRequestConfig } from "axios"
import { apiClient } from "@/lib/axios"
import { clearTokens, getAccessToken, getRefreshToken, setAccessToken } from "@/lib/tokenStorage"
import { ApiError } from "@/api/error"

declare module "axios" {
  export interface AxiosRequestConfig {
    /** When true, skip Authorization attachment and 401→refresh retry. */
    skipAuthRefresh?: boolean
    /** Internal: already retried after refresh. */
    _retry?: boolean
  }
}

interface ApiEnvelope<T> {
  code: string
  message: string
  responsedAt: string
  data?: T
  success?: boolean
}

interface RefreshTokenData {
  accessToken: string
}

type FetcherConfig = Pick<AxiosRequestConfig, "params" | "signal">

interface GetArgs {
  path: string
  headers?: AxiosRequestConfig["headers"]
  config?: FetcherConfig
}

interface MutateArgs {
  path: string
  body?: unknown
  headers?: AxiosRequestConfig["headers"]
  config?: FetcherConfig
}

let refreshPromise: Promise<string> | null = null

function toApiError(status: number, payload: unknown): ApiError {
  if (payload && typeof payload === "object") {
    const body = payload as Partial<ApiEnvelope<unknown>>
    const code = typeof body.code === "string" ? body.code : String(status)
    const message = typeof body.message === "string" ? body.message : "요청을 처리할 수 없습니다."
    return new ApiError(status, code, message)
  }
  return new ApiError(status, String(status), "요청을 처리할 수 없습니다.")
}

function unwrapEnvelope<T>(status: number, payload: unknown): T {
  if (!payload || typeof payload !== "object") {
    throw new ApiError(status, String(status), "응답 형식이 올바르지 않습니다.")
  }

  const body = payload as ApiEnvelope<T>

  if (body.success === false) {
    throw toApiError(status, body)
  }

  if (body.success === true) {
    return body.data as T
  }

  // Error responses omit `success` (conventions.md).
  if (typeof body.code === "string" && typeof body.message === "string" && !("data" in body)) {
    throw toApiError(status, body)
  }

  if ("data" in body) {
    return body.data as T
  }

  return undefined as T
}

async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    clearTokens()
    throw new ApiError(401, "401", "로그인이 필요합니다.")
  }

  const response = await apiClient.post<ApiEnvelope<RefreshTokenData>>(
    "/auth/refresh",
    { refreshToken },
    { skipAuthRefresh: true },
  )

  const data = unwrapEnvelope<RefreshTokenData>(response.status, response.data)
  if (!data?.accessToken) {
    clearTokens()
    throw new ApiError(401, "401", "토큰 재발급에 실패했습니다.")
  }

  setAccessToken(data.accessToken)
  return data.accessToken
}

function refreshAccessTokenSingleFlight(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = refreshAccessToken().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (config.skipAuthRefresh) {
    return config
  }

  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!error || typeof error !== "object" || !("config" in error)) {
      throw new ApiError(0, "NETWORK_ERROR", "네트워크 오류가 발생했습니다.")
    }

    const axiosError = error as {
      config?: InternalAxiosRequestConfig
      response?: { status: number; data: unknown }
      message?: string
    }

    const config = axiosError.config
    const status = axiosError.response?.status ?? 0

    if (
      status === 401 &&
      config &&
      !config._retry &&
      !config.skipAuthRefresh &&
      getRefreshToken()
    ) {
      config._retry = true
      try {
        const accessToken = await refreshAccessTokenSingleFlight()
        config.headers.Authorization = `Bearer ${accessToken}`
        return apiClient.request(config)
      } catch {
        clearTokens()
        throw toApiError(401, axiosError.response?.data)
      }
    }

    if (axiosError.response) {
      throw toApiError(status, axiosError.response.data)
    }

    throw new ApiError(0, "NETWORK_ERROR", axiosError.message ?? "네트워크 오류가 발생했습니다.")
  },
)

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await apiClient.request<ApiEnvelope<T>>(config)
  return unwrapEnvelope<T>(response.status, response.data)
}

export const api = {
  get<T>({ path, headers, config }: GetArgs): Promise<T> {
    return request<T>({ method: "GET", url: path, headers, ...config })
  },
  post<T>({ path, body, headers, config }: MutateArgs): Promise<T> {
    return request<T>({ method: "POST", url: path, data: body, headers, ...config })
  },
  put<T>({ path, body, headers, config }: MutateArgs): Promise<T> {
    return request<T>({ method: "PUT", url: path, data: body, headers, ...config })
  },
  patch<T>({ path, body, headers, config }: MutateArgs): Promise<T> {
    return request<T>({ method: "PATCH", url: path, data: body, headers, ...config })
  },
  delete<T>({ path, headers, config }: GetArgs): Promise<T> {
    return request<T>({ method: "DELETE", url: path, headers, ...config })
  },
}
