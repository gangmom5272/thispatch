import { gameHandlers } from "./gameHandlers"
import { authHandlers } from "./handlers/authHandlers"
import { healthHandlers } from "./handlers/healthHandlers"
import { reactionTrendsHandlers } from "./handlers/reactionTrendsHandlers"
import { sessionHandlers } from "./handlers/sessionHandlers"

export const handlers = [
  ...healthHandlers,
  ...sessionHandlers,
  ...authHandlers,
  ...gameHandlers,
  ...reactionTrendsHandlers,
]
