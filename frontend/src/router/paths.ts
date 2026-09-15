/** App route absolute paths — single source for Link/`navigate` and router registration. */
export const paths = {
  home: "/",
  games: "/games",
  gameDetailPattern: "/games/:gameId",
  methodology: "/methodology",
  login: "/login",
  signup: "/signup",
} as const

export const GAME_DETAIL_TABS = {
  reactionTrends: "reaction-trends",
  playtimeTopics: "playtime-topics",
  reviews: "reviews",
  languageAnalysis: "language-analysis",
  plan: "plan",
} as const

export type GameDetailTab = (typeof GAME_DETAIL_TABS)[keyof typeof GAME_DETAIL_TABS]

export const DEFAULT_GAME_DETAIL_TAB: GameDetailTab = GAME_DETAIL_TABS.reactionTrends

export const GAME_DETAIL_TAB_LABELS: Record<GameDetailTab, string> = {
  [GAME_DETAIL_TABS.reactionTrends]: "반응 추세",
  [GAME_DETAIL_TABS.playtimeTopics]: "플레이타임 · 토픽",
  [GAME_DETAIL_TABS.reviews]: "리뷰",
  [GAME_DETAIL_TABS.languageAnalysis]: "언어별 분석",
  [GAME_DETAIL_TABS.plan]: "기획안 입력",
}

export const GAME_DETAIL_MAIN_TABS: GameDetailTab[] = [
  GAME_DETAIL_TABS.reactionTrends,
  GAME_DETAIL_TABS.playtimeTopics,
  GAME_DETAIL_TABS.reviews,
  GAME_DETAIL_TABS.languageAnalysis,
]

/** All detail segments for nested route registration (main tabs + plan CTA). */
export const GAME_DETAIL_TAB_ORDER: GameDetailTab[] = [
  ...GAME_DETAIL_MAIN_TABS,
  GAME_DETAIL_TABS.plan,
]

/** Default tab URL (reaction-trends). Bare `/games/:id` still redirects via index route. */
export function gameDetailPath(gameId: string | number): string {
  return gameDetailTabPath(gameId, DEFAULT_GAME_DETAIL_TAB)
}

export function gameDetailTabPath(gameId: string | number, tab: GameDetailTab): string {
  return `/games/${gameId}/${tab}`
}

/** Nested route segment under `/` (leading slash removed). */
export function routeSegment(absolutePath: string): string {
  return absolutePath.replace(/^\//, "")
}
