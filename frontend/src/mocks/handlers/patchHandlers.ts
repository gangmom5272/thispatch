import { delay, http, HttpResponse } from "msw"
import type {
  CaseGroup,
  CaseSearch,
  CaseSearchInput,
  PlanStructure,
  SimilarCase,
} from "../../types"
import { mockGames } from "../games"
import { userFromAuthHeader } from "../lib/authStore"
import { errorBody, okEnvelope } from "../lib/envelope"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

const sample: Omit<PlanStructure, "gameId" | "rawText" | "genreIds"> = {
  entities: [
    { id: 1, name: "Axebot", role: "ENEMY", source: "RULE", editable: false },
    { id: 2, name: "고통 4", role: "DIFFICULTY", source: "GAME_LEXICON", editable: false },
    { id: 3, name: "Wraith", role: "UNKNOWN", source: "UNKNOWN", editable: false },
  ],
  slots: [
    {
      id: 1,
      targetName: "Axebot",
      targetRole: "ENEMY",
      attribute: "HP",
      direction: "INCREASE",
      magnitude: "+20%",
      scope: "고통 4 이상",
      editable: true,
    },
    {
      id: 2,
      targetName: "Axebot",
      targetRole: "ENEMY",
      attribute: "ATK",
      direction: "INCREASE",
      magnitude: "+10%",
      scope: "고통 4 이상",
      editable: true,
    },
    {
      id: 3,
      targetName: "Wraith",
      targetRole: "UNKNOWN",
      attribute: "출현 빈도",
      direction: "MODIFY",
      magnitude: null,
      scope: null,
      editable: true,
    },
  ],
  restatement: {
    text: "적(Axebot)의 체력·공격력을 상향하고, 적용 범위는 고통 4 이상으로 제한하는 변경으로 이해했습니다.",
    highlights: {
      primaryRole: "ENEMY",
      attributes: ["HP", "ATK"],
      direction: "INCREASE",
      scope: "고통 4 이상",
    },
    warnings: [
      {
        code: "UNKNOWN_ENTITY",
        message: "Wraith는 UNKNOWN이라 검색 가중치가 낮습니다.",
        entityName: "Wraith",
      },
    ],
  },
}

function similarCase(
  gameId: number,
  genres: number[],
  patchTitle: string,
  patchedOn: string,
  similarity: number,
  reviewCount: number,
  before: number,
  after: number,
  avgInterval: number,
  ratio: number | null,
  commonalitySummary: string,
  differenceSummary: string,
): SimilarCase {
  const game = mockGames.find((item) => item.id === gameId)
  return {
    gameId,
    gameTitle: game?.title ?? "알 수 없는 게임",
    capsuleImageUrl: game?.capsuleImageUrl ?? null,
    genres,
    patchId: `${reviewCount}`,
    patchTitle,
    patchedOn,
    similarity,
    reviewCount,
    positiveRateBefore: before,
    positiveRateAfter: after,
    deltaPp: Math.round((after - before) * 10) / 10,
    avgPatchIntervalDays: avgInterval,
    nextPatchIntervalDays: ratio === null ? null : Math.round(avgInterval * ratio),
    followUpSpeedRatio: ratio,
    commonalitySummary,
    differenceSummary,
    comparison: {
      commonalities: [{ title: "변경 방향이 같습니다.", description: commonalitySummary }],
      differences: [{ title: "적용 범위가 다릅니다.", description: differenceSummary }],
    },
  }
}

const sampleGroups: CaseGroup[] = [
  {
    outcome: "NEGATIVE_SHIFT",
    name: "부정 급변",
    caseCount: 23,
    observedPatterns: [
      "복수 핵심 수치를 한 패치에서 동시 조정",
      "전체 플레이어 대상으로 적용",
      "기존 메타 빌드에 직접 영향",
    ],
    cases: [
      similarCase(
        646570,
        [1, 8],
        "v2.1.0",
        "2025-03-18",
        92.4,
        2418,
        81.2,
        54.6,
        12.4,
        0.4,
        "로그라이크 덱빌더로 장르가 같고, 적 능력치를 올려 플레이어가 불리해지는 방향의 조정이라는 점이 초안과 같습니다.",
        "층별 난이도 전체를 조정해 상위 난이도 한정인 초안보다 적용 범위가 넓고, 후속 패치까지 평소 주기의 0.4배로 빠르게 이어졌습니다.",
      ),
      similarCase(
        1245620,
        [3, 6],
        "v1.8.3",
        "2024-11-02",
        88.1,
        1036,
        76.4,
        58.9,
        21.0,
        1.8,
        "플레이어에게 불리한 방향의 수치 하향을 한 회차에 적용한 점이 초안이 검토 중인 조건과 같습니다.",
        "장비 제작 중심 던전 크롤러라 세션 구조가 다르고, 평소 패치 주기는 21.0일입니다. 후속 패치까지 평소 주기의 1.8배가 걸렸습니다.",
      ),
    ],
  },
  {
    outcome: "NO_CHANGE",
    name: "변화 없음",
    caseCount: 19,
    observedPatterns: [],
    cases: [
      similarCase(
        2379780,
        [1],
        "v3.4.1",
        "2025-05-09",
        90.7,
        1742,
        72.8,
        72.1,
        9.8,
        1.0,
        "로그라이크 단일 플레이 구조이고, 상위 난이도 구간을 조정 대상으로 삼은 점이 초안과 겹칩니다.",
        "긍정률이 0.7%p만 움직여 반응이 거의 변하지 않았고, 후속 패치도 평소 주기대로 이어졌습니다.",
      ),
      similarCase(
        1086940,
        [4],
        "v1.2.6",
        "2024-12-14",
        85.3,
        884,
        69.5,
        70.4,
        17.2,
        null,
        "불리 항목과 유리 항목을 한 회차에 함께 넣은 구성이 초안과 같습니다.",
        "턴제 전술이라 한 판 길이와 반복 구조가 다르고, 후속 패치 기록이 없어 이후 조정 여부를 확인할 수 없습니다.",
      ),
    ],
  },
  {
    outcome: "POSITIVE_SHIFT",
    name: "긍정 급변",
    caseCount: 5,
    observedPatterns: [],
    cases: [
      similarCase(
        1145360,
        [2, 3],
        "v2.0.0",
        "2025-01-22",
        87.6,
        3205,
        64.1,
        79.3,
        6.2,
        0.6,
        "적 능력치를 직접 조정한 회차이고, 변경 대상이 리뷰에서 이미 반복 언급되던 항목이라는 점이 초안과 겹칩니다.",
        "협동 4인 액션 RPG라 난이도 체감 구조가 다르고, 후속 패치까지 평소 주기의 0.6배가 걸렸습니다.",
      ),
      similarCase(
        367520,
        [4],
        "v1.5.0",
        "2024-09-30",
        81.9,
        1190,
        58.7,
        70.2,
        13.5,
        0.9,
        "적용 범위를 상위 난이도 구간으로 한정한 점이 초안과 같습니다.",
        "직전 회차의 불만을 되돌리는 방향이라 변경 부호가 초안과 반대이고, 긍정률이 11.5%p 올랐습니다.",
      ),
    ],
  },
]

function repeatCases(cases: SimilarCase[], count: number): SimilarCase[] {
  return Array.from({ length: count }, (_, index) => {
    const base = cases[index % cases.length]
    const round = Math.floor(index / cases.length)
    return round === 0
      ? base
      : {
          ...base,
          patchId: `${base.patchId}-${round}`,
          patchTitle: `${base.patchTitle}.${round}`,
          similarity: Math.max(50, base.similarity - round * 3.1),
          reviewCount: Math.max(100, base.reviewCount - round * 137),
        }
  })
}

const emptyGroups: CaseGroup[] = sampleGroups.map((group) => ({
  ...group,
  caseCount: 0,
  observedPatterns: [],
  cases: [],
}))

export const patchHandlers = [
  http.post(`${baseURL}/games/:gameId/plan-structures`, async ({ request, params }) => {
    await delay(600)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const game = mockGames.find((item) => item.id === Number(params.gameId))
    if (!game) {
      return HttpResponse.json(errorBody("404", "게임을 찾을 수 없습니다."), { status: 404 })
    }
    const body = (await request.json()) as { text?: string }
    const text = body.text?.trim()
    if (!text) {
      return HttpResponse.json(errorBody("400", "기획안 본문이 비어 있습니다."), { status: 400 })
    }
    const data: PlanStructure = {
      gameId: game.id,
      rawText: text,
      genreIds: game.tags.map((tag) => tag.id),
      ...sample,
    }
    return HttpResponse.json(okEnvelope(data))
  }),
  http.post(`${baseURL}/games/:gameId/case-searches`, async ({ request, params }) => {
    await delay(800)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const game = mockGames.find((item) => item.id === Number(params.gameId))
    if (!game) {
      return HttpResponse.json(errorBody("404", "게임을 찾을 수 없습니다."), { status: 404 })
    }
    const body = (await request.json()) as Partial<CaseSearchInput>
    if (!Array.isArray(body.confirmedSlots) || body.confirmedSlots.length === 0 || !body.sort) {
      return HttpResponse.json(
        errorBody("400", "요청 본문의 필수 필드 또는 형식이 올바르지 않습니다."),
        { status: 400 },
      )
    }
    const genreIds = body.genreIds ?? []
    const groups = genreIds.length === 0 ? emptyGroups : sampleGroups
    const data: CaseSearch = {
      status: "COMPLETED",
      gameId: game.id,
      confirmedSlots: body.confirmedSlots,
      genreIds,
      sort: body.sort,
      totalCount: groups.reduce((sum, group) => sum + group.caseCount, 0),
      groups: groups.map((group) => ({
        ...group,
        cases: repeatCases(group.cases, group.caseCount).sort(
          (a, b) => b.reviewCount - a.reviewCount,
        ),
      })),
      notices: [
        "유사도는 변경 슬롯 임베딩 유사도(0~100)이며 성공 확률이 아닙니다.",
        "후속 배수는 해당 패치~다음 패치 간격 / 평균 패치 주기입니다.",
      ],
    }
    return HttpResponse.json(okEnvelope(data), { status: 201 })
  }),
]
