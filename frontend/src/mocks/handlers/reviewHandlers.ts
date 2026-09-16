import { delay, http, HttpResponse } from "msw"
import { mockGames } from "@/mocks/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { addDaysIso, todaySeoul } from "@/lib/seoulDate"
import { REVIEW_TOPICS, type Review, type ReviewsListData } from "@/types/review"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")
const DEFAULT_LIMIT = 10

/** MSW 공통 envelope 응답. */
function respond(status: number, message: string, data?: unknown) {
  return HttpResponse.json(
    {
      code: String(status),
      message,
      responsedAt: new Date().toISOString().slice(0, 19).replace("T", " "),
      ...(data === undefined ? {} : { data }),
      success: status === 200,
    },
    { status },
  )
}

function isAuthorized(request: Request) {
  return userFromAuthHeader(request) !== null
}

/** 태그 id로 ReviewTag를 만든다. */
function tagOf(id: number) {
  const topic = REVIEW_TOPICS.find((t) => t.id === id)
  return { id, name: topic?.name ?? `토픽 ${id}` }
}

const BODIES = [
  "상위 난이도에서 드로우가 줄면서 기존 덱 아키타입이 전부 무너졌다. 장기 플레이어 입장에선 사실상 다른 게임이 됐다.",
  "보상 재화까지 같이 줄어서 반복 플레이 동기가 사라졌다. 두 변경을 한 패치에 같이 넣은 게 문제.",
  "치명 크래시가 드디어 고쳐졌다. 이번 패치에서 이건 확실히 좋아진 부분.",
  "입문 난이도에서는 체감 변화가 거의 없다. 처음 시작하는 사람에게는 여전히 추천할 만하다.",
  "난이도 표기를 바꿨는데 인게임 설명이 그대로라 무엇이 고쳐진 건지 모르겠다.",
  "UI 스케일이 깨져서 툴팁이 화면 밖으로 나간다. 해상도 바꿀 때마다 재설정해야 한다.",
  "서버 점검이 너무 잦아서 피크 타임에 못 들어간다. 공지도 늦게 올라온다.",
  "과금 패키지 구성이 이전보다 나빠졌다. 같은 가격에 재화가 줄었다.",
  "밸런스 패치 이후 즐기던 빌드가 전부 막혀서 장기 플레이어만 피해를 본다.",
  "최적화 패치 후 프레임이 안정됐다. 저사양에서도 플레이가 가능해졌다.",
  "매치메이킹이 이상하다. 숙련도 차이가 너무 큰 상대와 자주 붙는다.",
  "번역 품질이 떨어져서 퀘스트 설명을 이해하기 어렵다.",
  "이벤트 보상이 빈약하다. 접속 유인으로 쓰기엔 부족하다.",
  "컨트롤러 지원이 아직 불완전하다. 일부 메뉴에서 커서가 멈춘다.",
  "엔드게임 루프가 반복적이다. 새 콘텐츠가 더 필요하다.",
  "버프가 과도해서 PvP가 한쪽으로 기울었다.",
  "크래시 로그가 쌓이는데 재현 조건이 불명확하다.",
  "운영 측 응답이 느려서 환불·제재 문의가 밀린다.",
  "시즌 패스가 비싸 보이지만 구성은 나쁘지 않다.",
  "초반 튜토리얼이 길어 이탈이 생길 것 같다.",
  "사운드 믹스가 이상해서 보이스를 듣기 어렵다.",
  "친구 초대가 실패하는 경우가 잦다.",
  "맵 로딩이 길어서 파티 플레이가 끊긴다.",
  "신규 캐릭터 스킬 설명이 모호하다.",
  "패치 노트와 실제 체감이 다르다. 수치 공개를 더 해줬으면 한다.",
] as const

const LANGUAGES = ["english", "schinese", "japanese", "russian", "koreana"] as const

/**
 * 게임별 목 리뷰 풀(25건). 태그 조합으로 OR 필터를 검증한다.
 */
function buildReviewPool(gameId: number): Review[] {
  const end = todaySeoul()
  // seed: 게임마다 플레이타임·도움됨이 달라 보이게
  const seed = gameId % 97

  return BODIES.map((body, index) => {
    // primaryTopic: 메인 토픽(1~5 순환), secondary: 일부만 추가
    const primaryTopic = (index % 5) + 1
    const secondaryTopic = index % 3 === 0 ? (primaryTopic % 5) + 1 : null
    const tags = [tagOf(primaryTopic)]
    if (secondaryTopic !== null && secondaryTopic !== primaryTopic) {
      tags.push(tagOf(secondaryTopic))
    }

    return {
      id: gameId * 1000 + index + 1,
      sentiment: index % 3 === 0 ? ("POSITIVE" as const) : ("NEGATIVE" as const),
      isUpdated: index % 2 === 0,
      playtimeMinutes: 600 + ((seed + index * 37) % 200) * 60,
      languageCode: LANGUAGES[index % LANGUAGES.length],
      helpfulCount: 40 + ((seed + index * 13) % 400),
      tags,
      body,
      reviewDate: addDaysIso(end, -(index % 14)),
    }
  })
}

/**
 * 게임·시드에 따라 대표 리뷰 3건을 만든다.
 * 긍정/부정·언어·태그를 섞어 카드 UI를 검증한다.
 */
function buildRepresentative(gameId: number): Review[] {
  return buildReviewPool(gameId).slice(0, 3)
}

/** topicIds 쿼리(콤마 또는 반복 키)를 숫자 배열로 파싱한다. */
function parseTopicIds(url: URL): number[] {
  const fromAll = url.searchParams.getAll("topicIds")
  const rawParts = fromAll.length > 0 ? fromAll : []
  const ids = rawParts
    .flatMap((part) => part.split(","))
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isInteger(n) && n >= 1)
  return [...new Set(ids)]
}

/** 커서(offset 기반)를 파싱한다. 잘못된 값이면 null. */
function parseCursor(raw: string | null): number | null {
  if (raw === null || raw === "") return 0
  try {
    const parsed = JSON.parse(atob(raw)) as { offset?: unknown }
    const offset = Number(parsed.offset)
    return Number.isInteger(offset) && offset >= 0 ? offset : null
  } catch {
    return null
  }
}

function encodeCursor(offset: number): string {
  return btoa(JSON.stringify({ offset }))
}

/** 목록 meta·페이지를 조립한다. */
function buildListPayload(
  gameId: number,
  topicIds: number[],
  offset: number,
  limit: number,
): ReviewsListData {
  const end = todaySeoul()
  const start = addDaysIso(end, -13)
  const pool = buildReviewPool(gameId)
  // filtered: 선택 토픽 중 하나라도 태그 id가 겹치면 포함(OR)
  const filtered =
    topicIds.length === 0
      ? pool
      : pool.filter((review) => review.tags.some((tag) => topicIds.includes(tag.id)))

  const slice = filtered.slice(offset, offset + limit)
  const nextOffset = offset + slice.length
  const hasNext = nextOffset < filtered.length

  return {
    meta: {
      period: { startDate: start, endDate: end, dayCount: 14 },
      timezone: "Asia/Seoul",
      aggregationBasis: "UPDATED_AT",
      lastCollectedAt: `${end}T08:00:00Z`,
    },
    items: slice,
    page: {
      limit,
      nextCursor: hasNext ? encodeCursor(nextOffset) : null,
      hasNext,
      totalCount: filtered.length,
    },
  }
}

export const reviewHandlers = [
  http.get(`${baseURL}/games/:gameId/reviews/representative`, async ({ params, request }) => {
    await delay(200)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    return respond(200, "OK", buildRepresentative(gameId))
  }),

  http.get(`${baseURL}/games/:gameId/reviews`, async ({ params, request }) => {
    await delay(250)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }

    const url = new URL(request.url)
    const topicIds = parseTopicIds(url)
    const offset = parseCursor(url.searchParams.get("cursor"))
    if (offset === null) {
      return respond(400, "유효하지 않은 페이지 커서입니다.")
    }
    // limit: 기본 10, 1~100
    const limitRaw = Number(url.searchParams.get("limit") ?? DEFAULT_LIMIT)
    const limit =
      Number.isInteger(limitRaw) && limitRaw >= 1 && limitRaw <= 100 ? limitRaw : DEFAULT_LIMIT

    return respond(200, "OK", buildListPayload(gameId, topicIds, offset, limit))
  }),
]
