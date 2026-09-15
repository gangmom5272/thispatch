import { gameHandlers } from "./gameHandlers"
import { authHandlers } from "./handlers/authHandlers"
import { healthHandlers } from "./handlers/healthHandlers"
import { reactionTrendsHandlers } from "./handlers/reactionTrendsHandlers"
import { playtimeTopicsHandlers } from "./handlers/playtimeTopicsHandlers"
import { patchHandlers } from "./handlers/patchHandlers"
import { sessionHandlers } from "./handlers/sessionHandlers"

export const handlers = [
  ...healthHandlers,
  ...sessionHandlers,
  ...authHandlers,
  ...gameHandlers,
  ...reactionTrendsHandlers,
  ...playtimeTopicsHandlers,
  ...patchHandlers,
]
