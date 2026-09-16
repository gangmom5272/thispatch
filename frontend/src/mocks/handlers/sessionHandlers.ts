import { http, HttpResponse } from "msw"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { okEnvelope } from "@/mocks/lib/envelope"

export const sessionHandlers = [
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
]
