import axios from "axios"
import type { AxiosRequestConfig } from "axios"
import { useAuthStore } from "../store/useAuthStore"
import { ApiError } from "./error"

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api",
  timeout: 10_000,
})

client.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`)
  }
  return config
})

interface RequestOptions {
  path: string
  body?: unknown
  headers?: AxiosRequestConfig["headers"]
  config?: Pick<AxiosRequestConfig, "params" | "signal">
}

function responseError(status: number, body: unknown): ApiError {
  if (typeof body === "object" && body !== null && "message" in body) {
    const code = "code" in body && typeof body.code === "string" ? body.code : String(status)
    if (typeof body.message === "string") {
      return new ApiError(status, code, body.message)
    }
  }
  return new ApiError(status, String(status), "요청을 처리하지 못했습니다. 다시 시도해 주세요.")
}

async function request<T>(method: "GET" | "POST" | "DELETE", options: RequestOptions): Promise<T> {
  try {
    const { data, status } = await client.request<unknown>({
      ...options.config,
      method,
      url: options.path,
      data: options.body,
      headers: options.headers,
    })
    if (
      typeof data !== "object" ||
      data === null ||
      !("success" in data) ||
      data.success !== true
    ) {
      throw responseError(status, data)
    }
    return ("data" in data ? data.data : undefined) as T
  } catch (error) {
    if (axios.isCancel(error)) {
      throw error
    }
    if (axios.isAxiosError(error)) {
      if (error.response) {
        throw responseError(error.response.status, error.response.data)
      }
      throw new ApiError(
        0,
        "NETWORK_ERROR",
        "서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.",
      )
    }
    throw error
  }
}

export const api = {
  get: <T>(options: RequestOptions) => request<T>("GET", options),
  post: <T>(options: RequestOptions) => request<T>("POST", options),
  delete: <T>(options: RequestOptions) => request<T>("DELETE", options),
}
