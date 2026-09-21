import { delay, http, HttpResponse } from "msw"
import { mockGames } from "@/mocks/games"
import { userFromAuthHeader } from "@/mocks/lib/authStore"
import { languageAnalysisTranslationById } from "@/mocks/handlers/languageAnalysisHandlers"
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

/** 원문(body) + 한국어 번역(translatedBody). koreana는 번역 null. 긴/짧은 본문 혼합으로 더보기 검증. */
const REVIEW_TEXTS = [
  {
    languageCode: "english",
    body: "High Ascension draws got nerfed and every deck archetype collapsed. For long-time players this is basically a different game. I used to clear A20 with three different builds, and after this patch none of them feel viable anymore. The draw reduction stacks with the new elite scaling so runs drag on forever, and the reward density did not go up to compensate. If you mainly play high Ascension you will feel punished for investing hundreds of hours. Newer players might not notice, but the dedicated community is already looking elsewhere.",
    translatedBody:
      "상위 난이도에서 드로우가 줄면서 기존 덱 아키타입이 전부 무너졌다. 장기 플레이어 입장에선 사실상 다른 게임이 됐다. 예전에는 서로 다른 빌드 세 가지로 A20을 깨곤 했는데, 이번 패치 이후로는 어느 쪽도 통하지 않는다. 드로우 감소가 엘리트 스케일링과 겹치면서 런이 끝없이 늘어지고, 보상 밀도는 그대로라 손해가 더 크다. 상위 난이도만 파는 사람은 수백 시간 투자가 벌 받는 느낌이다. 신규는 모를 수 있어도, 헤비 유저는 이미 다른 게임을 보고 있다.",
  },
  {
    languageCode: "schinese",
    body: "奖励被砍了。",
    translatedBody: "보상이 줄었다.",
  },
  {
    languageCode: "japanese",
    body: "致命的なクラッシュがやっと直った。今回のパッチでここは確実に良くなった。ただしローディングは相変わらず長く、パーティを組むとマップ切り替えのたびに会話が途切れる。コントローラーのカーソルもメニューによっては固まるので、キーボードに戻している。総合すると安定性は上がったが、快適さはまだ足りない。次のパッチではロードと入力周りを優先してほしい。",
    translatedBody:
      "치명 크래시가 드디어 고쳐졌다. 이번 패치에서 이건 확실히 좋아진 부분이다. 다만 로딩은 여전히 길고, 파티를 짜면 맵을 바꿀 때마다 대화가 끊긴다. 컨트롤러 커서도 메뉴에 따라 멈추길래 키보드로 돌아갔다. 종합하면 안정성은 올랐지만 쾌적함은 아직 부족하다. 다음 패치에서는 로드와 입력 쪽을 우선해 줬으면 한다.",
  },
  {
    languageCode: "russian",
    body: "На начальной сложности почти ничего не изменилось.",
    translatedBody: "입문 난이도에서는 체감 변화가 거의 없다.",
  },
  {
    languageCode: "koreana",
    body: "난이도 표기를 바꿨는데 인게임 설명이 그대로라 무엇이 고쳐진 건지 모르겠다. 설정 화면에 적힌 숫자와 실제 전투 체감이 다르고, 패치 노트에도 구체 수치가 빠져 있어서 커뮤니티마다 해석이 갈린다. 공식 위키가 업데이트되기 전까지는 시험 삼아 몇 판 돌려 보는 수밖에 없다.",
    translatedBody: null,
  },
  {
    languageCode: "english",
    body: "UI scale is broken so tooltips fly off-screen. I have to reset it every time I change resolution. On ultrawide the inventory grid also overlaps the quest tracker, and the font hinting looks blurry at 150%. This should have been caught in QA before shipping the HUD refresh.",
    translatedBody:
      "UI 스케일이 깨져서 툴팁이 화면 밖으로 나간다. 해상도 바꿀 때마다 재설정해야 한다. 울트라와이드에서는 인벤 그리드가 퀘스트 추적기와 겹치고, 150%에서 폰트 힌팅도 흐리다. HUD 개편 전에 QA에서 잡혔어야 할 문제다.",
  },
  {
    languageCode: "schinese",
    body: "服务器维护太频繁。",
    translatedBody: "서버 점검이 너무 잦다.",
  },
  {
    languageCode: "japanese",
    body: "課金パッケージの中身が以前より悪い。同じ値段なのに通貨が減った。",
    translatedBody: "과금 패키지 구성이 이전보다 나빠졌다. 같은 가격에 재화가 줄었다.",
  },
  {
    languageCode: "russian",
    body: "После балансного патча все любимые билды умерли. Страдают только олды. Новички приходят в уже сломанную мету и думают, что игра всегда такой была. Нужен откат или хотя бы компенсация ресурсов для пересборки билдов, иначе удержание будет падать быстрее, чем после прошлого нерфа.",
    translatedBody:
      "밸런스 패치 이후 즐기던 빌드가 전부 막혀서 장기 플레이어만 피해를 본다. 신규는 이미 망가진 메타로 들어와 원래 이런 게임인 줄 안다. 롤백이든 재빌드용 자원 보상이든 없으면, 지난 너프 때보다 이탈이 더 빨라질 것이다.",
  },
  {
    languageCode: "koreana",
    body: "최적화 패치 후 프레임이 안정됐다.",
    translatedBody: null,
  },
  {
    languageCode: "english",
    body: "Matchmaking is weird. I keep getting paired against people way above my skill. Ranked feels like a coin flip depending on whether the lobby has a smurf stack, and the hidden MMR swing after one loss is absurd. Solo queue should not punish you for three bad teammates in a row.",
    translatedBody:
      "매치메이킹이 이상하다. 숙련도 차이가 너무 큰 상대와 자주 붙는다. 랭크는 스머프 스택 여부에 따라 동전 던지기 같고, 한 판 진 뒤 숨은 MMR 출렁임도 심하다. 솔큐가 연속 트롤 팀원 때문에 처벌받으면 안 된다.",
  },
  {
    languageCode: "schinese",
    body: "翻译质量很差，任务说明根本看不懂。",
    translatedBody: "번역 품질이 떨어져서 퀘스트 설명을 이해하기 어렵다.",
  },
  {
    languageCode: "japanese",
    body: "イベント報酬が薄くて、ログイン動機としては弱い。",
    translatedBody: "이벤트 보상이 빈약하다. 접속 유인으로 쓰기엔 부족하다.",
  },
  {
    languageCode: "russian",
    body: "Поддержка контроллера ещё сырая. В части меню курсор зависает.",
    translatedBody: "컨트롤러 지원이 아직 불완전하다. 일부 메뉴에서 커서가 멈춘다.",
  },
  {
    languageCode: "koreana",
    body: "엔드게임 루프가 반복적이다. 새 콘텐츠가 더 필요하다. 주간 레이드 보상 테이블이 세 시즌째 거의 같고, 장인 콘텐츠도 숫자만 올린 재판이다. 스토리 에피소드라도 짧게 넣어 주면 접속 이유가 생길 텐데, 지금은 숙제만 돌다 로그아웃한다.",
    translatedBody: null,
  },
  {
    languageCode: "english",
    body: "The buffs are so strong that PvP is completely one-sided.",
    translatedBody: "버프가 과도해서 PvP가 한쪽으로 기울었다.",
  },
  {
    languageCode: "schinese",
    body: "崩溃日志一直在堆，但复现条件不明确。重启后偶尔好一点，但联机同步一抖就又崩。把日志打包해서 客服开了票，两周了还是自动回复。",
    translatedBody:
      "크래시 로그가 쌓이는데 재현 조건이 불명확하다. 재시작 후 잠깐 괜찮아지다가 온라인 동기화만 흔들려도 다시 죽는다. 로그를 묶어 문의했는데 이주째 자동 답변뿐이다.",
  },
  {
    languageCode: "japanese",
    body: "運営の返信が遅くて返金・制裁の問い合わせが滞っている。",
    translatedBody: "운영 측 응답이 느려서 환불·제재 문의가 밀린다.",
  },
  {
    languageCode: "russian",
    body: "Сезонный пропуск выглядит дорогим, но набор в целом нормальный.",
    translatedBody: "시즌 패스가 비싸 보이지만 구성은 나쁘지 않다.",
  },
  {
    languageCode: "koreana",
    body: "초반 튜토리얼이 길어 이탈이 생길 것 같다.",
    translatedBody: null,
  },
  {
    languageCode: "english",
    body: "The sound mix is off and I can barely hear the voice lines. Combat SFX drown everything, and when three ultimates overlap the compressor just turns into noise. Accessibility settings for dialogue boost would help a lot.",
    translatedBody:
      "사운드 믹스가 이상해서 보이스를 듣기 어렵다. 전투 SFX가 전부 덮고, 궁극기 세 개가 겹치면 컴프레서가 소음만 낸다. 대사 부스트 접근성 옵션이 있으면 큰 도움이 될 것이다.",
  },
  {
    languageCode: "schinese",
    body: "好友邀请经常失败。",
    translatedBody: "친구 초대가 실패하는 경우가 잦다.",
  },
  {
    languageCode: "japanese",
    body: "マップの読み込みが長くてパーティプレイが途切れる。",
    translatedBody: "맵 로딩이 길어서 파티 플레이가 끊긴다.",
  },
  {
    languageCode: "russian",
    body: "Описание скиллов новых персонажей слишком туманное.",
    translatedBody: "신규 캐릭터 스킬 설명이 모호하다.",
  },
  {
    languageCode: "koreana",
    body: "패치 노트와 실제 체감이 다르다. 수치 공개를 더 해줬으면 한다. 표기상의 +5%와 체감 배율이 안 맞고, 숨은 캡·내부 쿨다운 설명이 빠져 있다. 최소한 툴팁에 실적용 공식을 적어 달라.",
    translatedBody: null,
  },
] as const

/**
 * 게임별 목 리뷰 풀(25건). 태그 조합·번역 토글을 검증한다.
 */
function buildReviewPool(gameId: number): Review[] {
  const end = todaySeoul()
  // seed: 게임마다 플레이타임·도움됨이 달라 보이게
  const seed = gameId % 97

  return REVIEW_TEXTS.map((text, index) => {
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
      languageCode: text.languageCode,
      helpfulCount: 40 + ((seed + index * 13) % 400),
      tags,
      body: text.body,
      translatedBody: text.translatedBody,
      reviewDate: addDaysIso(end, -(index % 14)),
    }
  })
}

/**
 * 게임·시드에 따라 대표 리뷰 4건을 만든다.
 * 긍정/부정·언어·태그를 섞어 카드 UI를 검증한다.
 */
function buildRepresentative(gameId: number): Review[] {
  return buildReviewPool(gameId).slice(0, 4)
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
  http.get(`${baseURL}/reviews/:reviewId/translation`, async ({ params, request }) => {
    await delay(180)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }

    // reviewId: 경로 파라미터(숫자). 매직 id로 404/502 목 응답을 검증한다.
    const reviewId = Number(params.reviewId)
    if (!Number.isInteger(reviewId) || reviewId < 1) {
      return respond(404, "번역 대상을 찾을 수 없습니다.")
    }
    if (reviewId === 404404) {
      return respond(404, "번역 대상을 찾을 수 없습니다.")
    }
    if (reviewId === 502502) {
      return respond(502, "번역 서비스에 일시적으로 문제가 있습니다.")
    }

    // matched: 목 리뷰 풀에서 id로 찾은 항목(없으면 짧은 한국어 폴백)
    const matched = mockGames
      .flatMap((game) => buildReviewPool(game.id))
      .find((review) => review.id === reviewId)

    // translatedText: 목록 translatedBody → 언어 분석 body → 짧은 한국어 폴백
    const translatedText =
      matched?.translatedBody ??
      matched?.body ??
      languageAnalysisTranslationById.get(reviewId) ??
      "이 리뷰의 한국어 번역본입니다."

    return respond(200, "OK", { translatedText })
  }),

  http.get(`${baseURL}/games/:gameId/reviews/representative`, async ({ params, request }) => {
    await delay(200)
    if (!isAuthorized(request)) {
      return respond(401, "인증이 필요합니다.")
    }
    const gameId = Number(params.gameId)
    if (!mockGames.some((game) => game.id === gameId)) {
      return respond(404, "게임을 찾을 수 없습니다.")
    }
    return respond(200, "OK", {
      meta: { ...buildListPayload(gameId, [], 0, 4).meta, dataStatus: "AVAILABLE" },
      items: buildRepresentative(gameId),
    })
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
