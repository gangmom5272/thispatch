import { gameHandlers } from "./gameHandlers"
import { genreHandlers } from "./handlers/genreHandlers"
import { authHandlers } from "./handlers/authHandlers"
import { healthHandlers } from "./handlers/healthHandlers"
import { languageAnalysisHandlers } from "./handlers/languageAnalysisHandlers"
import { reactionTrendsHandlers } from "./handlers/reactionTrendsHandlers"
import { playtimeTopicsHandlers } from "./handlers/playtimeTopicsHandlers"
import { reviewHandlers } from "./handlers/reviewHandlers"
import { patchHandlers } from "./handlers/patchHandlers"
import { sessionHandlers } from "./handlers/sessionHandlers"
import { memberHandlers } from "./handlers/memberHandlers"

export const handlers = [
  ...healthHandlers,
  ...sessionHandlers,
  ...authHandlers,
  ...memberHandlers,
  ...gameHandlers,
  ...genreHandlers,
  ...reactionTrendsHandlers,
  ...playtimeTopicsHandlers,
  ...reviewHandlers,
  ...patchHandlers,
  ...languageAnalysisHandlers,
]
