import { delay, http, HttpResponse } from "msw"
import type {
  AnalysisMeta,
  LanguageAnalysis,
  LanguageAnalysisDetail,
  LanguageShare,
  RepresentativeReview,
} from "../../types"
import { mockGames } from "../games"
import { userFromAuthHeader } from "../lib/authStore"
import { errorBody, okEnvelope } from "../lib/envelope"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

const TOTAL_REVIEW_COUNT = 486
const MINIMUM_SAMPLE_COUNT = 30

const meta: AnalysisMeta = {
  period: { startDate: "2026-09-02", endDate: "2026-09-15", dayCount: 14 },
  timezone: "Asia/Seoul",
  aggregationBasis: "UPDATED_AT",
  dataStatus: "AVAILABLE",
}

const languageSeeds: Array<[string, string, number, number]> = [
  ["english", "영어", 41.2, 38.2],
  ["schinese", "중국어 간체", 18.6, 24.5],
  ["russian", "러시아어", 8.3, 40.1],
  ["korean", "한국어", 6.4, 47.3],
  ["japanese", "일본어", 5.1, 58.6],
  ["polish", "폴란드어", 1.4, 50],
  ["turkish", "터키어", 0.9, 33.3],
]

const languages: LanguageShare[] = languageSeeds.map(([code, name, share, rate]) => {
  const reviewCount = Math.round((TOTAL_REVIEW_COUNT * share) / 100)
  const positiveCount = Math.round((reviewCount * rate) / 100)
  return {
    languageCode: code,
    displayName: name,
    reviewCount,
    reviewShare: share,
    positiveRate: rate,
    positiveCount,
    negativeCount: reviewCount - positiveCount,
    isSufficientSample: reviewCount >= MINIMUM_SAMPLE_COUNT,
  }
})

type ReviewSeed = Omit<RepresentativeReview, "id" | "languageCode" | "isUpdated">

const reviewSeeds: Record<string, { summary: string; reviews: ReviewSeed[] }> = {
  english: {
    summary:
      "드로우 스케일링 하향을 보상 재화 감소와 한 패치에 넣은 결정을 문제로 집는 문장이 가장 많습니다. 변경 폭 자체보다 두 조정이 겹쳐 체감이 커진 점을 지적하는 비중이 높고, 재조정 요구가 반복됩니다.",
    reviews: [
      {
        sentiment: "NEGATIVE",
        playtimeMinutes: 17880,
        helpfulCount: 412,
        tags: [
          { id: 1, name: "밸런스" },
          { id: 2, name: "공지" },
        ],
        body: "베타 기간도 없이 한 패치에 너프가 두 개나 들어갔습니다. A15 덱이 말 그대로 작동하지 않고, 보상 감소 때문에 다시 파밍하는 데 두 배가 걸립니다. 드로우 상한 자체는 이해할 수 있지만, 같은 주에 재화까지 줄인 건 검증 없이 밀어붙인 느낌입니다. 지금은 상위 난이도에서 빌드 다양성이 오히려 줄었고, 커뮤니티 덱 목록 절반이 쓸 수 없게 됐습니다. 둘 중 하나는 되돌리거나 최소한 수치를 공개해 주세요.",
        originalBody:
          "Two nerfs in one patch with no beta window. My A15 deck literally does not function anymore and the reward cut means regrinding it takes twice as long. I can live with the draw cap on its own, but shipping the currency cut in the same week feels untested. Build diversity at high ascension has actually gone down and half the community deck lists are dead. Roll one of them back, or at least publish the numbers.",
        reviewDate: "2026-09-11",
      },
      {
        sentiment: "NEGATIVE",
        playtimeMinutes: 9840,
        helpfulCount: 287,
        tags: [
          { id: 1, name: "밸런스" },
          { id: 3, name: "재화" },
        ],
        body: "A12 이상 드로우 스택이 강했다는 건 이해하지만, 보상 15% 삭감을 같이 넣은 건 징벌처럼 느껴집니다. 둘 중 하나만 되돌리면 이 리뷰를 바꾸겠습니다.",
        originalBody:
          "I get that A12+ draw stacking was strong, but pairing it with a 15% reward cut just feels punitive. Roll one of them back and I will change this review.",
        reviewDate: "2026-09-14",
      },
    ],
  },
  korean: {
    summary:
      "난이도 상향 자체보다 패치 노트에 변경 폭이 명시되지 않은 점을 지적하는 리뷰가 많습니다. 긍정 리뷰는 상위 난이도의 긴장감이 살아났다는 평가에 집중됩니다.",
    reviews: [
      {
        sentiment: "POSITIVE",
        playtimeMinutes: 25200,
        helpfulCount: 96,
        tags: [{ id: 4, name: "난이도" }],
        body: "고통 15 이상에서 다시 긴장감이 생겼습니다. 드로우만으로 굴러가던 덱이 정리된 건 오히려 반갑네요.",
        originalBody: null,
        reviewDate: "2026-09-09",
      },
      {
        sentiment: "NEGATIVE",
        playtimeMinutes: 6120,
        helpfulCount: 74,
        tags: [{ id: 2, name: "공지" }],
        body: "패치 노트에 수치가 없어서 뭐가 얼마나 바뀐 건지 직접 세어봐야 했습니다. 변경 자체보다 설명 부족이 더 불만입니다.",
        originalBody: null,
        reviewDate: "2026-09-12",
      },
    ],
  },
  japanese: {
    summary:
      "치명적 크래시 수정은 긍정적으로 평가되지만, 로딩 시간과 컨트롤러 입력 문제가 여전하다는 지적이 함께 나타납니다. 다음 패치에서 로드와 입력 주변을 우선해 달라는 요구가 반복됩니다.",
    reviews: [
      {
        sentiment: "NEGATIVE",
        playtimeMinutes: 6840,
        helpfulCount: 96,
        tags: [{ id: 5, name: "시스템/UI·편의성" }],
        body: "치명적인 크래시가 드디어 고쳐졌다. 이번 패치로 이 부분은 확실히 좋아졌다. 다만 로딩은 여전히 길고, 파티를 짜면 맵을 전환할 때마다 대화가 끊긴다. 컨트롤러 커서도 메뉴에 따라 멈춰서 키보드로 돌아갔다. 종합하면 안정성은 올라갔지만 쾌적함은 아직 부족하다. 다음 패치에서는 로드와 입력 주변을 우선해 주길 바란다.",
        originalBody:
          "致命的なクラッシュがやっと直った。今回のパッチでここは確実に良くなった。ただしローディングは相変わらず長く、パーティを組むとマップ切り替えのたびに会話が途切れる。コントローラーのカーソルもメニューによっては固まるので、キーボードに戻している。総合すると安定性は上がったが、快適さはまだ足りない。次のパッチではロードと入力周りを優先してほしい。",
        reviewDate: "2026-09-14",
      },
      {
        sentiment: "POSITIVE",
        playtimeMinutes: 19200,
        helpfulCount: 63,
        tags: [{ id: 4, name: "난이도" }],
        body: "상위 난이도가 다시 재미있어졌다.",
        originalBody: "高難度がまた面白くなった。",
        reviewDate: "2026-09-10",
      },
    ],
  },
  schinese: {
    summary:
      "보상 재화 감소를 가장 큰 불만으로 꼽는 리뷰가 다수이며, 난이도 조정 자체에 대한 언급은 상대적으로 적습니다.",
    reviews: [
      {
        sentiment: "NEGATIVE",
        playtimeMinutes: 4320,
        helpfulCount: 158,
        tags: [{ id: 3, name: "재화" }],
        body: "보상을 15% 깎은 뒤로 한 판 끝내도 남는 게 없습니다. 난이도 조정은 받아들일 수 있지만 재화까지 줄이면 반복 플레이 동기가 사라집니다. 신규 유물 4종은 좋았는데, 그걸 모으려면 이전보다 훨씬 오래 걸려서 결국 체감상 콘텐츠가 줄어든 셈입니다.",
        originalBody:
          "奖励砍了15%之后，打完一局基本什么都剩不下。难度调整可以接受，但连货币也一起削，重复游玩的动力就没了。新增的4件遗物不错，可是要凑齐比以前慢太多，实际上等于内容变少了。",
        reviewDate: "2026-09-13",
      },
      {
        sentiment: "POSITIVE",
        playtimeMinutes: 30600,
        helpfulCount: 71,
        tags: [{ id: 1, name: "밸런스" }],
        body: "드로우 상한은 필요한 조정이었습니다. 이제 다른 빌드도 시도할 이유가 생겼습니다.",
        originalBody: "抽牌上限是必要的调整。现在终于有理由尝试其他构筑了。",
        reviewDate: "2026-09-08",
      },
    ],
  },
}

const fallbackSeed = {
  summary:
    "전투 시간이 길어졌다는 의견이 반복적으로 나타나며, 보상 변경에 대한 언급은 상대적으로 적습니다.",
  reviews: [
    {
      sentiment: "NEGATIVE",
      playtimeMinutes: 7200,
      helpfulCount: 58,
      tags: [{ id: 1, name: "밸런스" }],
      body: "적 체력 증가 이후 전투가 너무 길어졌습니다.",
      originalBody: null,
      reviewDate: "2026-09-10",
    },
    {
      sentiment: "POSITIVE",
      playtimeMinutes: 13500,
      helpfulCount: 41,
      tags: [{ id: 4, name: "난이도" }],
      body: "후반 난이도가 살아나서 다시 플레이하게 됐습니다.",
      originalBody: null,
      reviewDate: "2026-09-13",
    },
  ] satisfies ReviewSeed[],
}

/** 언어 코드별 목 리뷰 id 베이스(번역 API MSW 조회용). */
const LANGUAGE_REVIEW_ID_BASE: Record<string, number> = {
  english: 910_000,
  korean: 911_000,
  japanese: 912_000,
  schinese: 913_000,
}

/**
 * 언어 분석 대표 리뷰 id → 한국어 표시 본문(body).
 * GET /reviews/{id}/translation MSW가 조회한다.
 */
export const languageAnalysisTranslationById = new Map<number, string>()

/** 시드 리뷰에 고유 id를 붙이고 번역 맵을 채운다. */
function withReviewIds(languageCode: string, reviews: ReviewSeed[]): RepresentativeReview[] {
  // base: 언어별 id 구간 시작
  const base = LANGUAGE_REVIEW_ID_BASE[languageCode] ?? 919_000
  return reviews.map((review, index) => {
    // id: 언어 구간 + 1-based index
    const id = base + index + 1
    languageAnalysisTranslationById.set(id, review.body)
    return {
      ...review,
      id,
      languageCode,
      isUpdated: index === 0,
    }
  })
}

// 번역 API MSW가 상세 조회 전에도 id를 찾을 수 있게 시드를 미리 등록한다.
for (const [languageCode, seed] of Object.entries(reviewSeeds)) {
  withReviewIds(languageCode, seed.reviews)
}
withReviewIds("fallback", fallbackSeed.reviews)

function unauthorized() {
  return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
}

function gameNotFound() {
  return HttpResponse.json(errorBody("404", "게임을 찾을 수 없습니다."), { status: 404 })
}

export const languageAnalysisHandlers = [
  http.get(`${baseURL}/games/:gameId/language-analysis`, async ({ request, params }) => {
    await delay(400)
    if (!userFromAuthHeader(request)) {
      return unauthorized()
    }
    if (!mockGames.some((game) => game.id === Number(params.gameId))) {
      return gameNotFound()
    }
    const data: LanguageAnalysis = {
      meta,
      totalReviewCount: TOTAL_REVIEW_COUNT,
      isSufficientSample: TOTAL_REVIEW_COUNT >= MINIMUM_SAMPLE_COUNT,
      minimumSampleCount: MINIMUM_SAMPLE_COUNT,
      languages,
    }
    return HttpResponse.json(okEnvelope(data))
  }),
  http.get(
    `${baseURL}/games/:gameId/language-analysis/:languageCode`,
    async ({ request, params }) => {
      await delay(500)
      if (!userFromAuthHeader(request)) {
        return unauthorized()
      }
      if (!mockGames.some((game) => game.id === Number(params.gameId))) {
        return gameNotFound()
      }
      const languageCode = String(params.languageCode)
      const language = languages.find((item) => item.languageCode === languageCode)
      if (!language) {
        return HttpResponse.json(errorBody("400", "언어 코드가 올바르지 않습니다."), {
          status: 400,
        })
      }
      const seed = reviewSeeds[languageCode] ?? fallbackSeed
      const usedReviewCount = Math.min(language.reviewCount, 40)
      const data: LanguageAnalysisDetail = {
        meta,
        languageCode,
        summary: {
          status: "COMPLETED",
          text: seed.summary,
          targetPeriod: meta.period,
          targetReviewCount: language.reviewCount,
          usedReviewCount,
          selection: {
            code: "HELPFUL_DESC",
            limit: 40,
            description: `${language.reviewCount}건 중 도움됨 상위 ${usedReviewCount}건`,
          },
        },
        representativeReviews: withReviewIds(languageCode, seed.reviews),
      }
      return HttpResponse.json(okEnvelope(data))
    },
  ),
]
