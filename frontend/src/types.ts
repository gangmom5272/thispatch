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

/** GET /games/{gameId} */
export interface GameDetail {
  id: number
  capsuleImageUrl: string | null
  title: string
  tags: GameTag[]
  positiveRate: number | null
  isMine: boolean
  description: string | null
  releasedOn: string | null
  reviewCount: number | null
  lastCollectedAt: string | null
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

export interface GameDetail {
  id: number
  capsuleImageUrl: string | null
  title: string
  tags: GameTag[]
  positiveRate: number | null
  isMine: boolean
  description: string | null
  releasedOn: string | null
  reviewCount: number | null
  lastCollectedAt: string | null
}

export interface PlanEntity {
  id: number
  name: string
  role: string
  source: string
  editable: boolean
}

export interface PlanSlot {
  id: number
  targetName: string
  targetRole: string
  attribute: string
  direction: string
  magnitude: string | null
  scope: string | null
  editable: boolean
}

export interface PlanWarning {
  code: string
  message: string
  entityName: string | null
}

export interface PlanRestatement {
  text: string
  highlights: {
    primaryRole: string
    attributes: string[]
    direction: string
    scope: string | null
  }
  warnings: PlanWarning[]
}

export interface PlanStructure {
  gameId: number
  rawText: string
  genreIds: number[]
  entities: PlanEntity[]
  slots: PlanSlot[]
  restatement: PlanRestatement
}
