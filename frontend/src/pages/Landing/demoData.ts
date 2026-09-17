import type { Review } from "@/types/review"
import type { PlaytimeTopicRow, PlaytimeTopicsAiSummary } from "@/types/statistics"

/**
 * 랜딩 전용 표시 데이터. thispatch.pen `SB / 00 랜딩 페이지`의 값을 그대로 옮겼다.
 * 랜딩에서는 분석 API를 호출하지 않으므로(plan 5절) 화면 구성용 고정값으로 둔다.
 */

export interface MarqueeGame {
  appId: number
  name: string
  genres: [string, string]
  positiveRate: number
  capsuleImageUrl: string
}

/** 게임 목록 카드와 같은 Steam 상점 이미지 경로. */
const steamCapsuleUrl = (appId: number) =>
  `https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${appId}/header.jpg`

/** Hero 상단 트랙(왼→오)에 흐르는 게임 목록 카드. */
export const MARQUEE_GAMES: MarqueeGame[] = (
  [
    [646570, "Slay the Spire", ["로그라이크", "덱빌딩"], 97.2],
    [553850, "HELLDIVERS 2", ["슈팅", "협동"], 75.4],
    [1245620, "ELDEN RING", ["액션 RPG", "소울라이크"], 92.1],
    [1517290, "Battlefield 2042", ["FPS", "멀티플레이어"], 44.3],
    [413150, "Stardew Valley", ["캐주얼", "힐링"], 98.0],
    [1716740, "Starfield", ["오픈월드", "RPG"], 58.6],
    [892970, "Valheim", ["서바이벌", "크래프팅"], 94.2],
    [427520, "Factorio", ["시뮬레이션", "자동화"], 98.4],
    [1091500, "Cyberpunk 2077", ["액션", "RPG"], 83.5],
  ] as Array<[number, string, [string, string], number]>
).map(([appId, name, genres, positiveRate]) => ({
  appId,
  name,
  genres,
  positiveRate,
  capsuleImageUrl: steamCapsuleUrl(appId),
}))

export interface MarqueeReview {
  id: number
  sentiment: "POSITIVE" | "NEGATIVE"
  meta: string
  body: string
  helpfulCount: number
  tags: [string, string]
}

/** Hero 하단 트랙(오→왼)에 흐르는 리뷰 카드. */
export const MARQUEE_REVIEWS: MarqueeReview[] = [
  {
    id: 1,
    sentiment: "POSITIVE",
    meta: "호평 · 312h · korean",
    body: "밸런스 패치 이후로 하위 덱도 굴러간다. 상위 난이도에서 선택지가 늘어난 게 확실히 체감된다.",
    helpfulCount: 412,
    tags: ["밸런스", "난이도"],
  },
  {
    id: 2,
    sentiment: "NEGATIVE",
    meta: "혹평 · 194h · english",
    body: "A12 이상에서 드로우가 줄면서 기존 아키타입이 전부 무너졌다. 사실상 다른 게임이 됐다.",
    helpfulCount: 287,
    tags: ["너프", "상위 난이도"],
  },
  {
    id: 3,
    sentiment: "POSITIVE",
    meta: "호평 · 87h · japanese",
    body: "최적화 패치 이후 프레임 드랍이 거의 사라졌다. 이제야 제대로 된 게임을 하는 느낌.",
    helpfulCount: 196,
    tags: ["최적화", "성능"],
  },
  {
    id: 4,
    sentiment: "NEGATIVE",
    meta: "혹평 · 431h · korean",
    body: "시즌 패스 가격이 올라간 만큼 콘텐츠가 늘지 않았다. 반복 퀘스트만 계속 추가된다.",
    helpfulCount: 523,
    tags: ["가격", "콘텐츠"],
  },
  {
    id: 5,
    sentiment: "POSITIVE",
    meta: "호평 · 156h · english",
    body: "신규 유저 튜토리얼이 훨씬 친절해졌다. 친구를 데려오기 부담이 없어진 건 큰 변화다.",
    helpfulCount: 341,
    tags: ["온보딩", "신규 유저"],
  },
  {
    id: 6,
    sentiment: "NEGATIVE",
    meta: "혹평 · 62h · chinese",
    body: "매치메이킹 대기 시간이 패치 후 두 배로 늘었다. 저녁 시간대가 아니면 게임을 못 잡는다.",
    helpfulCount: 178,
    tags: ["매치메이킹", "대기"],
  },
  {
    id: 7,
    sentiment: "POSITIVE",
    meta: "호평 · 240h · korean",
    body: "모드 지원이 공식화되면서 커뮤니티가 다시 살아났다. 업데이트 방향이 마음에 든다.",
    helpfulCount: 265,
    tags: ["모드", "커뮤니티"],
  },
  {
    id: 8,
    sentiment: "NEGATIVE",
    meta: "혹평 · 318h · english",
    body: "서버 롤백으로 이틀치 진행이 날아갔다. 보상은 있었지만 신뢰가 회복되진 않는다.",
    helpfulCount: 604,
    tags: ["서버", "안정성"],
  },
]

export const TREND_PATCH_LABEL = "v1.4.0"
export const TREND_PATCH_DAY_LABEL = "16"

/**
 * 02 질문 섹션 — 반응 추세 그래프에 그릴 값. thispatch.pen 의 일별 칸 구성을 따른다.
 * 패치일과 그 이후는 값을 두지 않는다. 화면에서도 그 구간을 가려 알 수 없음을 나타낸다.
 */
export const TREND_KNOWN_DAYS = [
  { label: "09", positiveRate: 79.1, firstWritten: 26, updated: 5 },
  { label: "10", positiveRate: 78.6, firstWritten: 23, updated: 6 },
  { label: "11", positiveRate: 79.4, firstWritten: 29, updated: 4 },
  { label: "12", positiveRate: 78.2, firstWritten: 31, updated: 7 },
  { label: "13", positiveRate: 78.4, firstWritten: 24, updated: 6 },
  { label: "14", positiveRate: 77.6, firstWritten: 27, updated: 5 },
  { label: "15", positiveRate: 78.9, firstWritten: 22, updated: 7 },
]

/** 패치 직후로 날짜만 이어지는 칸. 이 뒤로는 라벨 없이 어둠으로 이어진다. */
export const TREND_UNKNOWN_DAY_LABELS = ["17", "18", "19", "20", "21", "22"]

/** 03 진단 섹션 — 선택 구간 리뷰 수. */
export const DIAGNOSIS_REVIEW_COUNT = 66

/** 03 진단 섹션 — 토픽별 언급률. overallMentionRate는 mentionRate - differencePp. */
export const DIAGNOSIS_TOPICS: PlaytimeTopicRow[] = [
  {
    topicId: 1,
    name: "밸런스 / 너프·버프",
    mentionCount: 45,
    mentionRate: 68.2,
    overallMentionRate: 43.6,
    differencePp: 24.6,
    highestBand: null,
  },
  {
    topicId: 5,
    name: "과금 / 재화(BM)",
    mentionCount: 27,
    mentionRate: 40.9,
    overallMentionRate: 23.4,
    differencePp: 17.5,
    highestBand: null,
  },
  {
    topicId: 2,
    name: "최적화 / 버그·크래시",
    mentionCount: 15,
    mentionRate: 22.7,
    overallMentionRate: 26.0,
    differencePp: -3.3,
    highestBand: null,
  },
  {
    topicId: 3,
    name: "시스템 / UI·편의성",
    mentionCount: 12,
    mentionRate: 18.2,
    overallMentionRate: 18.9,
    differencePp: -0.7,
    highestBand: null,
  },
  {
    topicId: 4,
    name: "외부 / 운영 이슈",
    mentionCount: 8,
    mentionRate: 12.1,
    overallMentionRate: 11.3,
    differencePp: 0.8,
    highestBand: null,
  },
]

const DEMO_PERIOD = { startDate: "2025-03-01", endDate: "2025-03-28", dayCount: 28 }

/** 03 진단 섹션 — AI 대표 반응 요약. */
export const DIAGNOSIS_AI_SUMMARY: PlaytimeTopicsAiSummary = {
  meta: {
    period: DEMO_PERIOD,
    timezone: "Asia/Seoul",
    aggregationBasis: "UPDATED_AT",
    dataStatus: "AVAILABLE",
  },
  selectedBand: "B3",
  summary: {
    status: "COMPLETED",
    text: "기존 빌드와 캐릭터 선택지가 줄었다는 불만이 반복되고, 보상 재화 감소가 반복 플레이 동기를 떨어뜨렸다는 언급이 함께 나옵니다.",
    recurringExpressions: [
      "빌드 다양성",
      "선택지 감소",
      "연쇄 방출",
      "보상 재화",
      "반복 플레이 동기",
      "고통 4",
    ],
    targetPeriod: DEMO_PERIOD,
    targetReviewCount: DIAGNOSIS_REVIEW_COUNT,
    usedReviewCount: 20,
    selection: null,
    reasonCode: null,
  },
  evidenceReviews: [],
}

export type OutcomeGroup = "negative" | "neutral" | "positive"

export interface OutcomeCase {
  group: OutcomeGroup
  groupLabel: string
  gameName: string
  description: string
  version: string
  similarity: number
  positiveRateBefore: number
  positiveRateAfter: number
  cadence: string
  followUp: string
  shared: string
  different: string
}

/** 04 비교 섹션 — 세 결과군 사례. */
export const OUTCOME_CASES: OutcomeCase[] = [
  {
    group: "negative",
    groupLabel: "부정 급변",
    gameName: "Grim Ascension",
    description: "로그라이크 덱빌더 · 층별 난이도 상승",
    version: "v2.1.0 · 2025-03-18 · 리뷰 2,418건",
    similarity: 92.4,
    positiveRateBefore: 81.2,
    positiveRateAfter: 54.6,
    cadence: "평소 패치 주기 12.4일",
    followUp: "후속 패치까지 · 평소 주기의 0.4배",
    shared:
      "로그라이크 덱빌더로 장르가 같고, 적 능력치를 올려 플레이어가 불리해지는 방향의 조정이라는 점이 초안과 같습니다.",
    different:
      "층별 난이도 전체를 조정해 상위 난이도 한정인 초안보다 적용 범위가 넓고, 후속 패치까지 평소 주기의 0.4배로 빠르게 이어졌습니다.",
  },
  {
    group: "neutral",
    groupLabel: "변화 없음",
    gameName: "Spire of Ash",
    description: "로그라이크 · 단일 플레이 중심",
    version: "v3.4.1 · 2025-05-09 · 리뷰 1,742건",
    similarity: 90.7,
    positiveRateBefore: 72.8,
    positiveRateAfter: 72.1,
    cadence: "평소 패치 주기 9.8일",
    followUp: "후속 패치까지 · 평소 주기의 1.0배",
    shared:
      "로그라이크 단일 플레이 구조이고, 상위 난이도 구간을 조정 대상으로 삼은 점이 초안과 겹칩니다.",
    different:
      "긍정률이 0.7%p만 움직여 반응이 거의 변하지 않았고, 후속 패치도 평소 주기대로 이어졌습니다.",
  },
  {
    group: "positive",
    groupLabel: "긍정 급변",
    gameName: "Ember Covenant",
    description: "액션 RPG · 협동 4인",
    version: "v2.0.0 · 2025-01-22 · 리뷰 3,205건",
    similarity: 87.6,
    positiveRateBefore: 64.1,
    positiveRateAfter: 79.3,
    cadence: "평소 패치 주기 6.2일",
    followUp: "후속 패치까지 · 평소 주기의 0.6배",
    shared:
      "적 능력치를 직접 조정한 회차이고, 변경 대상이 리뷰에서 이미 반복 언급되던 항목이라는 점이 초안과 겹칩니다.",
    different:
      "협동 4인 액션 RPG라 난이도 체감 구조가 다르고, 후속 패치까지 평소 주기의 0.6배가 걸렸습니다.",
  },
]

/** 05 근거 섹션 — 분석에서 원문으로 이어지는 네 단계. */
export const EVIDENCE_STEPS = [
  { step: "01", name: "긍정률", hint: "패치 전후로 반응이 얼마나 움직였는지 먼저 봅니다." },
  { step: "02", name: "토픽 분석", hint: "어떤 주제에서 이야기가 몰렸는지 좁혀 봅니다." },
  { step: "03", name: "AI 요약", hint: "반복되는 반응을 한 번에 읽습니다." },
  { step: "04", name: "리뷰 원문", hint: "마지막에는 유저가 쓴 문장을 그대로 확인합니다." },
] as const

/** 05 근거 섹션 01단계 — 패치 전후 긍정률. */
export const EVIDENCE_RATE = {
  periodLabel: "v2.4.0 패치 전후 14일 · 수정일 기준 집계",
  before: 78.4,
  after: 60.6,
  reviewCount: DIAGNOSIS_REVIEW_COUNT,
  positiveCount: 40,
  negativeCount: 26,
}

/** 05 근거 섹션 — 최근 대표 리뷰 원문. */
export const EVIDENCE_REVIEWS: Review[] = [
  {
    id: 194,
    sentiment: "NEGATIVE",
    isUpdated: true,
    playtimeMinutes: 11640,
    languageCode: "english",
    helpfulCount: 412,
    tags: [
      { id: 1, name: "밸런스/너프·버프" },
      { id: 6, name: "상위 난이도" },
    ],
    body: "A12 이상에서 드로우가 줄면서 기존 덱 아키타입이 전부 무너졌다. 상위 난이도를 계속 돌던 사람 입장에선 사실상 다른 게임이 됐다.",
    translatedBody: null,
    reviewDate: "2025-03-21",
  },
  {
    id: 271,
    sentiment: "NEGATIVE",
    isUpdated: true,
    playtimeMinutes: 16260,
    languageCode: "schinese",
    helpfulCount: 288,
    tags: [
      { id: 5, name: "과금/재화(BM)" },
      { id: 7, name: "반복 플레이" },
    ],
    body: "보상 재화까지 같이 줄어서 반복 플레이 동기가 사라졌다. 두 변경을 한 패치에 같이 넣은 게 문제.",
    translatedBody: null,
    reviewDate: "2025-03-23",
  },
]
