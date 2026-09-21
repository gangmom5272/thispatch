import { delay, http, HttpResponse } from "msw"
import type {
  CaseGroup,
  CaseSearch,
  CaseSearchInput,
  PatchDetail,
  PatchPlanHistoryDetail,
  PatchPlanHistoryItem,
  PlanStructure,
  SimilarCase,
} from "../../types"
import { mockGames } from "../games"
import { userFromAuthHeader } from "../lib/authStore"
import { errorBody, okEnvelope } from "../lib/envelope"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

/** 구조화가 발급한 planId. 후속 검색에서 소유 기획안인지 확인하는 데 쓴다. */
const structuredPlanIds = new Set<number>()
let nextPlanId = 1000

const sample: Omit<PlanStructure, "planId" | "gameId" | "rawText" | "genreIds"> = {
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
      changeType: "MODIFY",
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
      changeType: "MODIFY",
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
      changeType: "MODIFY",
      direction: "UNKNOWN",
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

const samplePlans: PatchPlanHistoryDetail[] = [
  {
    planId: 101,
    gameId: mockGames[0].id,
    gameTitle: mockGames[0].title,
    rawText:
      "Axebot의 체력을 20% 높이고 공격력을 10% 증가시킨다. 고통 4 이상 난이도에서만 적용하며, Wraith 계열 등장 빈도도 소폭 조정한다.",
    restatement: {
      text: "고통 4 이상 난이도에서 특정 적(Axebot)의 체력과 공격력을 함께 상향하는 변경입니다.",
    },
    genreIds: [9, 19],
    confirmedSlots: [
      {
        target: { name: "Axebot", role: "ENEMY" },
        attribute: "체력",
        changeType: "MODIFY",
        direction: "INCREASE",
        magnitude: "+20%",
        scope: "고통 4 이상",
      },
      {
        target: { name: "Axebot", role: "ENEMY" },
        attribute: "공격력",
        changeType: "MODIFY",
        direction: "INCREASE",
        magnitude: "+10%",
        scope: "고통 4 이상",
      },
      {
        target: { name: "Wraith", role: "UNKNOWN" },
        attribute: "등장 빈도",
        changeType: "MODIFY",
        direction: "UNKNOWN",
        magnitude: null,
        scope: null,
      },
    ],
    createdAt: "2026-09-21T14:32:00+09:00",
  },
  {
    planId: 100,
    gameId: mockGames[1].id,
    gameTitle: mockGames[1].title,
    rawText: "첫 번째 지역의 전투 보상 골드를 늘리고 상점 이용 부담을 낮춘다.",
    restatement: { text: "초반 구간의 보상을 늘려 진입 난이도를 낮추는 변경입니다." },
    genreIds: [],
    confirmedSlots: [
      {
        target: { name: "전투 보상", role: "REWARD" },
        attribute: "골드",
        changeType: "MODIFY",
        direction: "INCREASE",
        magnitude: "+15%",
        scope: "1지역",
      },
      {
        target: { name: "상점", role: "SHOP" },
        attribute: "가격",
        changeType: "MODIFY",
        direction: "DECREASE",
        magnitude: "-10%",
        scope: "1지역",
      },
    ],
    createdAt: "2026-09-18T10:16:00+09:00",
  },
  {
    planId: 99,
    gameId: mockGames[0].id,
    gameTitle: mockGames[0].title,
    rawText: "특정 카드의 비용을 낮추고 획득하는 방어도를 조정한다.",
    restatement: { text: "카드 비용과 방어도 수치를 함께 조정하는 변경입니다." },
    genreIds: [9],
    confirmedSlots: [
      {
        target: { name: "방어 카드", role: "CARD" },
        attribute: "비용",
        changeType: "MODIFY",
        direction: "DECREASE",
        magnitude: "-1",
        scope: null,
      },
      {
        target: { name: "방어 카드", role: "CARD" },
        attribute: "방어도",
        changeType: "MODIFY",
        direction: "DECREASE",
        magnitude: "-2",
        scope: null,
      },
    ],
    createdAt: "2026-09-15T16:40:00+09:00",
  },
  {
    planId: 98,
    gameId: mockGames[1].id,
    gameTitle: mockGames[1].title,
    rawText: "초반 엘리트 적의 체력을 낮추고 등장 조건을 조정한다.",
    restatement: { text: "초반 엘리트 전투의 난이도를 낮추는 변경입니다." },
    genreIds: [19],
    confirmedSlots: [
      {
        target: { name: "엘리트", role: "ENEMY" },
        attribute: "체력",
        changeType: "MODIFY",
        direction: "DECREASE",
        magnitude: "-10%",
        scope: "1막",
      },
      {
        target: { name: "엘리트", role: "ENEMY" },
        attribute: "등장 조건",
        changeType: "MODIFY",
        direction: "UNKNOWN",
        magnitude: null,
        scope: "1막",
      },
    ],
    createdAt: "2026-09-12T09:05:00+09:00",
  },
]

function planListItem(plan: PatchPlanHistoryDetail): PatchPlanHistoryItem {
  return {
    planId: plan.planId,
    gameId: plan.gameId,
    gameTitle: plan.gameTitle,
    rawTextPreview: plan.rawText.slice(0, 200),
    slotCount: plan.confirmedSlots.length,
    unknownEntityCount: new Set(
      plan.confirmedSlots
        .filter((slot) => slot.target.role === "UNKNOWN")
        .map((slot) => slot.target.name),
    ).size,
    createdAt: plan.createdAt,
  }
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
      commonalities: [
        { title: "변경 방향이 같습니다", description: commonalitySummary },
        {
          title: "장르와 런 구조가 같습니다",
          description:
            "둘 다 한 판이 짧게 끝나고 실패가 반복되는 구조라 난이도 상향의 체감이 같은 방식으로 쌓입니다.",
        },
      ],
      differences: [
        { title: "적용 범위가 다릅니다", description: differenceSummary },
        {
          title: "변경 폭과 대상 수가 다릅니다",
          description:
            "사례는 체력 +35% / 공격력 +25%를 보스 포함 6종에 적용했습니다. 초안은 체력 +20% / 공격력 +10%를 일반 적 1종에만 적용해 폭이 절반 수준입니다.",
        },
        {
          title: "동시 변경 여부가 다릅니다",
          description:
            "사례는 같은 회차에 보상 재화 −20%를 함께 넣어 반응이 어느 항목 때문인지 분리되지 않습니다. 초안은 적 상향만 담고 있습니다.",
        },
      ],
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

const samplePatchBody = [
  "밸런스",
  "- Warden 계열 적 체력 +35%",
  "- Warden 계열 적 공격력 +25%",
  "- 보스 조우 시 추가 페이즈 1개 도입",
  "",
  "경제",
  "- 전투 종료 보상 재화 획득량 -20%",
  "",
  "콘텐츠",
  "- 신규 유물 4종 추가",
  "- 일일 도전 모드 시드 교체 주기 단축",
].join("\n")

function findSampleCase(gameId: number, patchId: string): SimilarCase | undefined {
  const basePatchId = patchId.split("-")[0]
  return sampleGroups
    .flatMap((group) => group.cases)
    .find((item) => item.gameId === gameId && item.patchId === basePatchId)
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
    const planId = nextPlanId++
    structuredPlanIds.add(planId)
    const data: PlanStructure = {
      planId,
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
    if (typeof body.planId !== "number" || !Number.isInteger(body.planId) || body.planId < 1) {
      return HttpResponse.json(errorBody("VALIDATION_FAILED", "입력값을 확인해주세요."), {
        status: 400,
      })
    }
    if (
      !structuredPlanIds.has(body.planId) &&
      !samplePlans.some((plan) => plan.planId === body.planId)
    ) {
      return HttpResponse.json(
        errorBody("PATCH_PLAN_NOT_FOUND", "기획안 내역을 찾을 수 없습니다."),
        { status: 404 },
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
  http.get(`${baseURL}/games/:gameId/patches/:patchId`, async ({ request, params }) => {
    await delay(500)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const item = findSampleCase(Number(params.gameId), String(params.patchId))
    if (!item) {
      return HttpResponse.json(errorBody("404", "패치를 찾을 수 없습니다."), { status: 404 })
    }
    const data: PatchDetail = {
      patchId: String(params.patchId),
      gameId: item.gameId,
      title: `${item.gameTitle} ${item.patchTitle}`,
      patchedOn: item.patchedOn,
      publishedAt: `${item.patchedOn}T09:00:00Z`,
      body: samplePatchBody,
      bodyFormat: "PLAIN_TEXT",
      url: `https://store.steampowered.com/news/app/${item.gameId}/view/${params.patchId}`,
    }
    return HttpResponse.json(okEnvelope(data))
  }),

  http.get(`${baseURL}/patches/:patchId/translation`, async ({ request, params }) => {
    await delay(200)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }

    // patchId: news.gid 문자열. 매직 값으로 404/502 목 응답을 검증한다.
    const patchId = String(params.patchId)
    if (!patchId || patchId === "404404") {
      return HttpResponse.json(errorBody("404", "번역 대상을 찾을 수 없습니다."), { status: 404 })
    }
    if (patchId === "502502") {
      return HttpResponse.json(errorBody("502", "번역 서비스에 일시적으로 문제가 있습니다."), {
        status: 502,
      })
    }

    // reactionBodies: 반응 추세 목 패치의 한국어 번역
    const reactionTranslations: Record<string, { title: string; body: string }> = {
      "1001": {
        title: "v1.3.2 핫픽스",
        body: "핫픽스\n- 맵 로드 크래시 수정\n- 인벤토리 메모리 누수",
      },
      "1002": {
        title: "밸런스 패치",
        body: "밸런스\n- 워든 체력 +35%\n- 유물 드롭률 조정\n\n경제\n- 골드 싱크 재조정\n\n버그 수정\n- 2막 소프트락",
      },
      "1003": {
        title: "안정화 패치",
        body: "안정성\n- 네트워크 재시도 백오프\n- 세이브 손상 가드",
      },
    }

    const fromReaction = reactionTranslations[patchId]
    if (fromReaction) {
      return HttpResponse.json(
        okEnvelope({
          translatedTitle: fromReaction.title,
          translatedBody: fromReaction.body,
        }),
      )
    }

    // 사례·기타 패치: 이미 한국어인 샘플 본문을 번역문으로 반환
    return HttpResponse.json(
      okEnvelope({
        translatedTitle: "패치 노트 번역 제목",
        translatedBody: samplePatchBody,
      }),
    )
  }),
  http.get(`${baseURL}/members/me/patch-plans`, async ({ request }) => {
    await delay(400)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const url = new URL(request.url)
    const limit = Number(url.searchParams.get("limit") ?? 20)
    const offset = Number(url.searchParams.get("cursor") ?? 0)
    const page = samplePlans.slice(offset, offset + limit)
    const nextOffset = offset + page.length
    const hasNext = nextOffset < samplePlans.length
    return HttpResponse.json(
      okEnvelope({
        items: page.map(planListItem),
        page: {
          limit,
          nextCursor: hasNext ? String(nextOffset) : null,
          hasNext,
          totalCount: samplePlans.length,
        },
      }),
    )
  }),
  http.get(`${baseURL}/members/me/patch-plans/:planId`, async ({ request, params }) => {
    await delay(400)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const plan = samplePlans.find((item) => item.planId === Number(params.planId))
    if (!plan) {
      return HttpResponse.json(
        errorBody("PATCH_PLAN_NOT_FOUND", "기획안 내역을 찾을 수 없습니다."),
        { status: 404 },
      )
    }
    return HttpResponse.json(okEnvelope(plan))
  }),
]
