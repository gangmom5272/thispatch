import type { Game, PlanStructure, SimilarCase, PatchPlanHistoryDetail } from "../../src/types"

export const account = {
  email: "e2e@example.com",
  password: "Test-password-123!",
  nickname: "테스터",
}
export const genres = [
  { id: 1, name: "로그라이크" },
  { id: 2, name: "액션" },
]
export const games: Game[] = [
  {
    id: 7,
    title: "테스트 게임",
    positiveRate: 80,
    isMine: false,
    capsuleImageUrl: null,
    tags: genres,
    gameSummary: {
      id: 7,
      title: "테스트 게임",
      headerImageUrl: null,
      releasedOn: "2024-01-01",
      developer: "Studio A",
      playModes: ["싱글 플레이"],
      description: "첫 번째 게임 설명",
      userTags: [],
      reviewCount: 100,
      latestPatch: "1.1",
    },
  },
  {
    id: 8,
    title: "다른 게임",
    positiveRate: 60,
    isMine: false,
    capsuleImageUrl: null,
    tags: [genres[1]],
    gameSummary: {
      id: 8,
      title: "다른 게임",
      headerImageUrl: null,
      releasedOn: "2025-01-01",
      developer: "Studio B",
      playModes: [],
      description: "두 번째 게임 설명",
      userTags: [],
      reviewCount: 50,
      latestPatch: "2.1",
    },
  },
]
export function list<T>(items: T[], nextCursor: string | null = null, totalCount = items.length) {
  return {
    items,
    page: { limit: items.length, nextCursor, hasNext: nextCursor !== null, totalCount },
  }
}
export const period = { startDate: "2026-09-09", endDate: "2026-09-22", dayCount: 14 }
export const meta = {
  period,
  timezone: "Asia/Seoul",
  aggregationBasis: "UPDATED_AT",
  dataStatus: "AVAILABLE",
  lastCollectedAt: null,
}
export const summary = {
  status: "COMPLETED",
  text: "전투 개선에 대한 긍정적인 반응입니다.",
  targetPeriod: period,
  targetReviewCount: 40,
  usedReviewCount: 20,
  selection: { code: "HELPFUL_DESC", limit: 20, description: "도움됨 상위 20건" },
}
export const counts = {
  reviewCount: 10,
  positiveCount: 8,
  negativeCount: 2,
  positiveRate: 80,
  firstWrittenCount: 10,
  updatedCount: 0,
  firstWrittenPositiveCount: 8,
  firstWrittenNegativeCount: 2,
  updatedPositiveCount: 0,
  updatedNegativeCount: 0,
}
export const trends = {
  meta,
  availablePeriod: null,
  summary: { ...counts, firstWrittenPositiveRate: 80, updatedPositiveRate: null },
  daily: [{ ...counts, date: "2026-09-22", dataAvailable: true, patches: [] }],
}
export const plan: PlanStructure = {
  planId: 101,
  gameId: 7,
  rawText: "Axebot 체력을 20% 높인다.",
  genreIds: [1, 2],
  entities: [{ id: 1, name: "Axebot", role: "ENEMY", source: "GAME", editable: true }],
  slots: [
    {
      id: 1,
      targetName: "Axebot",
      targetRole: "ENEMY",
      attribute: "HP",
      changeType: "MODIFY",
      direction: "INCREASE",
      magnitude: "+20%",
      scope: "전체",
      editable: true,
    },
  ],
  restatement: {
    text: "적의 체력을 높이는 변경입니다.",
    highlights: { primaryRole: "ENEMY", attributes: ["HP"], direction: "INCREASE", scope: "전체" },
    warnings: [],
  },
}
export const savedPlan: PatchPlanHistoryDetail = {
  planId: 101,
  gameId: 7,
  gameTitle: games[0].title,
  rawText: plan.rawText,
  restatement: { text: plan.restatement.text },
  genreIds: [1, 2],
  createdAt: "2026-09-22T10:00:00+09:00",
  confirmedSlots: [
    {
      target: { name: "Axebot", role: "ENEMY" },
      attribute: "HP",
      changeType: "MODIFY",
      direction: "INCREASE",
      magnitude: "+20%",
      scope: "전체",
    },
  ],
}
export const similarCase: SimilarCase = {
  gameId: 8,
  gameTitle: "다른 게임",
  capsuleImageUrl: null,
  genres: [2],
  patchId: "patch-8",
  patchTitle: "전투 밸런스 패치",
  patchedOn: "2026-09-15",
  similarity: 88,
  reviewCount: 150,
  positiveRateBefore: 60,
  positiveRateAfter: 80,
  deltaPp: 20,
  avgPatchIntervalDays: 14,
  nextPatchIntervalDays: 7,
  followUpSpeedRatio: 0.5,
  commonalitySummary: "적 체력 조정",
  differenceSummary: "적용 난이도가 다름",
  comparison: {
    commonalities: [{ title: "체력 조정", description: "적의 체력을 높였습니다." }],
    differences: [{ title: "적용 범위", description: "다른 난이도에 적용했습니다." }],
  },
}
