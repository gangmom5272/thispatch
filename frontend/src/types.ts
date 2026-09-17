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

export interface PlanEntity {
  id: number
  name: string
  role: string
  source: string
  editable: boolean
}

export type PatchChangeType = "ADD" | "REMOVE" | "MODIFY" | "FIX" | "DEPRECATE"

export interface PlanSlot {
  id: number
  targetName: string
  targetRole: string
  attribute: string
  changeType: PatchChangeType
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

export type CaseSearchSort = "REVIEW_COUNT_DESC"

export type CaseOutcome = "NEGATIVE_SHIFT" | "NO_CHANGE" | "POSITIVE_SHIFT"

export interface ConfirmedSlot {
  magnitude: string | null
  target: { name: string; role: string }
  attribute: string
  changeType: PatchChangeType
  direction: string
  scope: string | null
}

export interface CaseSearchInput {
  confirmedSlots: ConfirmedSlot[]
  genreIds: number[]
  sort: CaseSearchSort
}

export interface CaseComparisonItem {
  title: string
  description: string
}

export interface SimilarCase {
  gameId: number
  gameTitle: string
  capsuleImageUrl: string | null
  genres: number[]
  patchId: string
  patchTitle: string
  patchedOn: string
  similarity: number
  reviewCount: number
  positiveRateBefore: number
  positiveRateAfter: number
  deltaPp: number
  avgPatchIntervalDays: number | null
  nextPatchIntervalDays: number | null
  followUpSpeedRatio: number | null
  commonalitySummary: string
  differenceSummary: string
  comparison: {
    commonalities: CaseComparisonItem[]
    differences: CaseComparisonItem[]
  }
}

export interface CaseGroup {
  outcome: CaseOutcome
  name: string
  caseCount: number
  observedPatterns: string[]
  cases: SimilarCase[]
}

/** POST /games/{gameId}/case-searches */
export interface CaseSearch extends CaseSearchInput {
  status: string
  gameId: number
  totalCount: number
  groups: CaseGroup[]
  notices: string[]
}

/** 사례 카드 → 사례 상세 비교 페이지로 넘기는 router state */
export interface CaseDetailLocationState {
  case: SimilarCase
  outcome: CaseOutcome
  outcomeName: string
}

/** GET /games/{gameId}/patches/{patchId} */
export interface PatchDetail {
  patchId: string
  gameId: number
  title: string
  patchedOn: string
  publishedAt: string
  body: string
  bodyFormat: string
  url: string
}
