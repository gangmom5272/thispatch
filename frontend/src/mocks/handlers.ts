import { gameHandlers } from "./gameHandlers"
import { authHandlers } from "./handlers/authHandlers"
import { healthHandlers } from "./handlers/healthHandlers"
import { languageAnalysisHandlers } from "./handlers/languageAnalysisHandlers"
import { patchHandlers } from "./handlers/patchHandlers"
import { sessionHandlers } from "./handlers/sessionHandlers"

export const handlers = [
  ...healthHandlers,
  ...sessionHandlers,
  ...authHandlers,
  ...gameHandlers,
  ...patchHandlers,
  ...languageAnalysisHandlers,
]
