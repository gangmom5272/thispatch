/** App route absolute paths — single source for Link/`navigate` and router registration. */
export const paths = {
  home: "/",
  games: "/games",
  gameDetailPattern: "/games/:gameId",
  gamePlanPattern: "/games/:gameId/plan",
  methodology: "/methodology",
  login: "/login",
  signup: "/signup",
} as const

export function gameDetailPath(gameId: string | number): string {
  return `/games/${gameId}`
}

export function gamePlanPath(gameId: string | number): string {
  return `/games/${gameId}/plan`
}

/** Nested route segment under `/` (leading slash removed). */
export function routeSegment(absolutePath: string): string {
  return absolutePath.replace(/^\//, "")
}
