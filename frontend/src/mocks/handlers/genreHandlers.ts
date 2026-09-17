import { delay, http, HttpResponse } from "msw"
import { GAME_GENRES } from "@/constants/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { errorBody, okEnvelope } from "@/mocks/lib/envelope"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

export const genreHandlers = [
  http.get(`${baseURL}/genres`, async ({ request }) => {
    await delay(200)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    return HttpResponse.json(okEnvelope({ items: GAME_GENRES }))
  }),
]
