import { http, HttpResponse } from "msw"
import { gameHandlers } from "./gameHandlers"

export const handlers = [
  ...gameHandlers,
  http.get("/api/health", () => {
    return HttpResponse.json({ status: "ok" })
  }),
]
