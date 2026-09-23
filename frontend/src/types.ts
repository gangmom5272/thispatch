export interface GameTag {
  id: number
  name: string
}

/** GET /genres */
export interface GenreList {
  items: GameTag[]
}

export interface Game {
  id: number
  capsuleImageUrl: string | null
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
  headerImageUrl: string | null
  releasedOn: string | null
  developer: string
  playModes: string[]
  description: string | null
  userTags: string[]
  reviewCount: number | null
  latestPatch: string
}

export type GameSort =
  "POSITIVE_RATE_ASC" | "REVIEW_COUNT_DESC" | "REACTION_CHANGE_DESC" | "RELEASE_DATE_DESC"

/** 출시연도·리뷰 수·긍정률 범위와 개발사 필터. 생략한 값은 요청 파라미터에서 빠진다. */
export interface GameRangeFilters {
  releaseYearFrom?: number
  releaseYearTo?: number
  minReviewCount?: number
  maxReviewCount?: number
  minPositiveRate?: number
  maxPositiveRate?: number
  developer?: string
}

export interface GameFilterConditions extends GameRangeFilters {
  sort: GameSort
  genreIds: number[]
}

export interface GameFilters extends GameRangeFilters {
  search?: string
  sort?: GameSort
  limit?: number
  genreIds?: number[]
}

/** GET /members/me/games — 내 게임 목록이므로 isMine을 포함하지 않는다. */
export type MyGame = Omit<Game, "isMine">

export interface GameList {
  items: Game[]
  page: {
    limit: number
    nextCursor: string | null
    hasNext: boolean
    totalCount: number
  }
}

export interface MyGameList extends Omit<GameList, "items"> {
  items: MyGame[]
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

/** POST /games/{gameId}/plan-structures */
export interface PlanStructure {
  /** 서버가 저장한 기획안 ID. 후속 유사 사례 검색 요청에 그대로 전달한다. */
  planId: number
  gameId: number
  rawText: string
  genreIds: number[]
  entities: PlanEntity[]
  slots: PlanSlot[]
  restatement: PlanRestatement
}

export type CaseSearchSort = "SIMILARITY_DESC" | "REVIEW_COUNT_DESC" | "PATCHED_ON_DESC"

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
  /** 구조화 응답 또는 기존 내역에서 받은 기획안 ID. 원문·최초 엔티티·해석은 재전송하지 않는다. */
  planId: number
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

/** POST /games/{gameId}/case-searches — 응답에는 planId가 없다. */
export interface CaseSearch extends Omit<CaseSearchInput, "planId"> {
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

export interface AnalysisPeriod {
  startDate: string
  endDate: string
  dayCount: number
}

export interface AnalysisMeta {
  period: AnalysisPeriod
  timezone: string
  aggregationBasis: string
  dataStatus: string
}

export interface LanguageShare {
  languageCode: string
  displayName: string
  reviewCount: number
  reviewShare: number
  positiveRate: number
  positiveCount: number
  negativeCount: number
  isSufficientSample: boolean
}

/** GET /games/{gameId}/language-analysis */
export interface LanguageAnalysis {
  meta: AnalysisMeta
  totalReviewCount: number
  isSufficientSample: boolean
  minimumSampleCount: number
  languages: LanguageShare[]
}

export type ReviewSentiment = "POSITIVE" | "NEGATIVE"

export interface RepresentativeReview {
  id: number
  sentiment: ReviewSentiment
  isUpdated: boolean
  playtimeMinutes: number | null
  languageCode: string
  helpfulCount: number
  tags: GameTag[]
  /**
   * 표시용 본문. 원문은 originalBody ?? body, 번역문은 GET /reviews/{id}/translation.
   */
  body: string
  /** 원문. 없으면 body를 원문으로 쓴다. */
  originalBody?: string | null
  /**
   * 목록 응답에 포함될 수 있는 번역 필드(백엔드 계약 유지).
   * 화면 번역 토글은 이 필드가 아니라 GET /reviews/{id}/translation 을 쓴다.
   */
  translatedBody?: string | null
  reviewDate: string
}

export interface LanguageSummary {
  status: "COMPLETED" | "SKIPPED" | "UNAVAILABLE"
  text: string | null
  targetPeriod: AnalysisPeriod
  targetReviewCount: number
  usedReviewCount: number | null
  selection: {
    code: string
    limit: number
    description: string
  } | null
  reasonCode?: "INSUFFICIENT_SAMPLE" | "AI_UNAVAILABLE"
  message?: string
}

/** GET /games/{gameId}/language-analysis/{languageCode} */
export interface LanguageAnalysisDetail {
  meta: AnalysisMeta
  languageCode: string
  summary: LanguageSummary
  representativeReviews: RepresentativeReview[]
}

export interface PatchPlanHistoryItem {
  planId: number
  gameId: number
  gameTitle: string
  rawTextPreview: string
  slotCount: number
  unknownEntityCount: number
  createdAt: string
}

/** GET /members/me/patch-plans */
export interface PatchPlanHistoryList {
  items: PatchPlanHistoryItem[]
  page: {
    limit: number
    nextCursor: string | null
    hasNext: boolean
    totalCount: number
  }
}

/** GET /members/me/patch-plans/{planId} */
export interface PatchPlanHistoryDetail {
  planId: number
  gameId: number
  gameTitle: string
  rawText: string
  restatement: { text: string }
  genreIds: number[]
  confirmedSlots: ConfirmedSlot[]
  createdAt: string
}
