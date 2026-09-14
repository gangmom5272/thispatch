export interface GameTag {
  id: number
  name: string
}

export interface Game {
  id: number
  capsuleImageUrl: string
  title: string
  tags: GameTag[]
  positiveRate: number
  isMine: boolean
  gameSummary: GameSummary
}

export interface GameSummary {
  id: number
  title: string
  headerImageUrl: string
  releasedAt: string
  developer: string
  playModes: string[]
  description: string
  userTags: string[]
  reviewCount: number
  latestPatch: string
}

export type GameSort =
  "POSITIVE_RATE_ASC" | "REVIEW_COUNT_DESC" | "REACTION_CHANGE_DESC" | "RELEASE_DATE_DESC"

export interface GameFilterConditions {
  sort: GameSort
  genreIds: number[]
}

export interface GameFilters {
  search?: string
  sort?: GameSort
  limit?: number
  genreIds?: number[]
}

export interface GameList {
  items: Game[]
  page: {
    limit: number
    nextCursor: string | null
    hasNext: boolean
    totalCount: number
  }
}
