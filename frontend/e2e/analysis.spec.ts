import { test, expect } from "./fixtures/test"
import { counts, list, meta, summary, trends } from "./fixtures/data"
import type { Review } from "../src/types/review"

const reviews: Review[] = [
  {
    id: 11,
    sentiment: "POSITIVE",
    isUpdated: false,
    playtimeMinutes: 100,
    languageCode: "english",
    helpfulCount: 10,
    tags: [{ id: 1, name: "밸런스/너프·버프" }],
    body: "Balance feels better after the update. ".repeat(25),
    reviewDate: "2026-09-20",
  },
  {
    id: 12,
    sentiment: "NEGATIVE",
    isUpdated: true,
    playtimeMinutes: 200,
    languageCode: "korean",
    helpfulCount: 5,
    tags: [{ id: 2, name: "최적화/버그/크래시" }],
    body: "업데이트 이후 크래시가 발생합니다.",
    reviewDate: "2026-09-21",
  },
]
const bandStats = {
  minMinutes: 0,
  maxMinutesExclusive: null,
  reviewCount: 40,
  classifiedReviewCount: 30,
  positiveCount: 32,
  negativeCount: 8,
  positiveRate: 80,
  isSufficientSample: true,
}

test.beforeEach(async ({ api }) => {
  api.set("GET /games/7/reviews/representative", () => ({ data: { meta, items: [] } }))
  api.set("GET /games/7/reviews", (request) => {
    const topicIds =
      new URL(request.url()).searchParams.get("topicIds")?.split(",").map(Number) ?? []
    return {
      data: {
        meta,
        ...list(
          reviews.filter(
            (review) => !topicIds.length || review.tags.some((tag) => topicIds.includes(tag.id)),
          ),
        ),
      },
    }
  })
  api.set("GET /reviews/11/translation", () => ({
    data: { reviewId: 11, translatedText: "업데이트 후 밸런스가 좋아졌습니다." },
  }))
  api.set("GET /games/7/playtime-topics", (request) => {
    const band = new URL(request.url()).searchParams.get("bandNo")
    return {
      data: {
        meta,
        selectedBand: band ? `B${band}` : "ALL",
        minimumSampleCount: 30,
        isSufficientSample: band !== "4",
        scale: {
          source: "ALL_GAME_REVIEWS",
          sampleCount: 160,
          p25Minutes: 60,
          medianMinutes: 120,
          p75Minutes: 180,
        },
        overall: { ...bandStats, band: "ALL" },
        bands: [1, 2, 3, 4].map((number) => ({
          ...bandStats,
          band: `B${number}`,
          minMinutes: (number - 1) * 60,
          maxMinutesExclusive: number === 4 ? null : number * 60,
          isSufficientSample: number !== 4,
        })),
        topics: [
          {
            topicId: 1,
            name: band ? "선택 구간 밸런스" : "전체 밸런스",
            mentionCount: 15,
            mentionRate: 50,
            overallMentionRate: 40,
            differencePp: 10,
            highestBand: null,
          },
        ],
        fallback: {
          reasonCode: "INSUFFICIENT_SAMPLE",
          message: "표본 부족",
          totalCount: 1,
          itemsByBand: [
            { band: "B4", items: [{ ...reviews[1], body: "오래 플레이한 사용자의 원문입니다." }] },
          ],
        },
      },
    }
  })
  api.set("GET /games/7/summaries/playtime-topics", () => ({
    data: {
      meta,
      selectedBand: "ALL",
      summary: { ...summary, recurringExpressions: [], evidenceReviews: [] },
    },
  }))
  api.set("GET /games/7/language-analysis", () => ({
    data: {
      meta,
      totalReviewCount: 80,
      isSufficientSample: true,
      minimumSampleCount: 30,
      languages: [
        { languageCode: "korean", displayName: "한국어" },
        { languageCode: "english", displayName: "영어" },
      ].map((language) => ({
        ...language,
        reviewCount: 40,
        reviewShare: 50,
        positiveRate: 80,
        positiveCount: 32,
        negativeCount: 8,
        isSufficientSample: true,
      })),
    },
  }))
  for (const language of ["korean", "english"]) {
    api.set(`GET /games/7/language-analysis/${language}/reviews`, () => ({
      data: {
        meta,
        languageCode: language,
        representativeReviews: [{ ...reviews[0], body: `${language} 리뷰 본문` }],
      },
    }))
    api.set(`GET /games/7/language-analysis/${language}/summary`, () => ({
      data: { meta, languageCode: language, summary: { ...summary, text: `${language} 요약` } },
    }))
  }
})

for (const [tab, name, heading] of [
  ["reaction-trends", "반응 추세", "패치 노트"],
  ["reviews", "리뷰", "리뷰 검색"],
  ["playtime-topics", "플레이타임 · 토픽", "플레이타임 구간별 토픽"],
  ["language-analysis", "언어별 분석", "언어별 반응 분포"],
]) {
  test(`${name} 탭 이동과 새로고침 및 직접 접근 시 게임을 유지한다`, async ({ page }) => {
    await page.goto("/games/7")
    await expect(page).toHaveURL(/\/reaction-trends$/)
    await page
      .getByRole("navigation", { name: "게임 상세 탭" })
      .getByRole("link", { name, exact: true })
      .click()
    await expect(page).toHaveURL(`http://localhost:5174/games/7/${tab}`)
    await expect(page.getByRole("link", { name, exact: true })).toHaveAttribute(
      "aria-current",
      "page",
    )
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole("heading", { name: "테스트 게임", exact: true })).toBeVisible()
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible()
  })
}

test("리뷰 토픽을 다중 선택하고 해제하면 목록과 건수가 갱신된다", async ({ page }) => {
  await page.goto("/games/7/reviews")
  await page.getByRole("button", { name: "밸런스/너프·버프", exact: true }).click()
  await expect(page.getByText("1건 / 전체 2건", { exact: true })).toBeVisible()
  await expect(page.getByText(reviews[1].body, { exact: true })).toBeHidden()
  await page.getByRole("button", { name: "최적화/버그/크래시", exact: true }).click()
  await expect(page.getByText("2건 / 전체 2건", { exact: true })).toBeVisible()
  await expect(page.getByText(reviews[1].body, { exact: true })).toBeVisible()
  await page.getByRole("button", { name: /선택 해제/ }).click()
  await expect(page.getByRole("button", { name: "밸런스/너프·버프", exact: true })).toHaveAttribute(
    "aria-pressed",
    "false",
  )
  await expect(page.getByText("2건 / 전체 2건", { exact: true })).toBeVisible()
})

test("긴 리뷰를 펼치고 번역 후 다시 원문으로 전환한다", async ({ page }) => {
  await page.goto("/games/7/reviews")
  const card = page.getByRole("article").filter({ hasText: "Balance feels better" })
  await card.getByRole("button", { name: "…더보기", exact: true }).click()
  await expect(card.getByRole("button", { name: "접기", exact: true })).toBeVisible()
  await card.getByRole("button", { name: "접기", exact: true }).click()
  await card.getByRole("button", { name: "번역", exact: true }).click()
  const translatedCard = page
    .getByRole("article")
    .filter({ hasText: "업데이트 후 밸런스가 좋아졌습니다." })
  await expect(translatedCard).toBeVisible()
  await translatedCard.getByRole("button", { name: "원문", exact: true }).click()
  await expect(card.getByText(reviews[0].body, { exact: true })).toBeVisible()
})

test("번역 실패를 표시해도 원문으로 돌아갈 수 있다", async ({ page, api }) => {
  api.set("GET /reviews/11/translation", () => ({ status: 503, message: "번역 요청 실패" }))
  await page.goto("/games/7/reviews")
  await page
    .getByRole("article")
    .filter({ hasText: "Balance feels better" })
    .getByRole("button", { name: "번역", exact: true })
    .click()
  const failedCard = page.getByRole("article").filter({ has: page.getByRole("alert") })
  await expect(failedCard.getByRole("alert")).toBeVisible({ timeout: 15000 })
  await failedCard.getByRole("button", { name: "원문", exact: true }).click()
  await expect(page.getByText(reviews[0].body, { exact: true })).toBeVisible()
})

test("조건에 맞는 리뷰가 없으면 빈 결과를 안내한다", async ({ page }) => {
  await page.goto("/games/7/reviews")
  await page.getByRole("button", { name: "과금/재화(BM)", exact: true }).click()
  await expect(
    page.getByText("조건에 맞는 리뷰가 없습니다. 토픽 선택을 바꿔 보세요."),
  ).toBeVisible()
  await expect(page.getByText("0건 / 전체 2건", { exact: true })).toBeVisible()
})

test("플레이타임 구간을 선택하면 분석이 바뀌고 표본 부족 구간은 원문을 표시한다", async ({
  page,
}) => {
  await page.goto("/games/7/playtime-topics")
  await expect(page.getByText("전체 밸런스", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: /^0분 – 1시간/ }).click()
  await expect(page.getByText("선택 구간 밸런스", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: /^3시간 이상/ }).click()
  await expect(page.getByRole("heading", { name: "리뷰 원문", exact: true })).toBeVisible()
  await expect(page.getByText("오래 플레이한 사용자의 원문입니다.", { exact: true })).toBeVisible()
  await expect(page.getByText("선택 구간 밸런스", { exact: true })).toBeHidden()
})

test("언어별 리뷰와 요약은 선택한 언어에 맞게 표시된다", async ({ page }) => {
  await page.goto("/games/7/language-analysis")
  await page.getByRole("button", { name: /한국어/ }).click()
  await expect(page.getByText("korean 리뷰 본문", { exact: true })).toBeVisible()
  await expect(page.getByText("korean 요약", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: /한국어/ }).click()
  await page.getByRole("button", { name: /영어/ }).click()
  await expect(page.getByText("english 리뷰 본문", { exact: true })).toBeVisible()
  await expect(page.getByText("english 요약", { exact: true })).toBeVisible()
  await expect(page.getByText("korean 리뷰 본문", { exact: true })).toBeHidden()
})

test("반응 추세에서 패치를 바꾸면 해당 패치 원문을 표시한다", async ({ page, api }) => {
  const markers = [1, 2].map((number) => ({
    id: `patch-${number}`,
    title: `패치 ${number}`,
    patchedOn: `2026-09-${20 + number}`,
    patchIndex: number,
    totalPatchCount: 2,
  }))
  api.set("GET /games/7/reaction-trends", () => ({
    data: {
      ...trends,
      daily: markers.map((patch) => ({
        ...counts,
        date: patch.patchedOn,
        dataAvailable: true,
        patches: [patch],
      })),
    },
  }))
  for (const patch of markers) {
    api.set(`GET /games/7/patches/${patch.id}`, () => ({
      data: {
        patchId: patch.id,
        gameId: 7,
        title: patch.title,
        patchedOn: patch.patchedOn,
        publishedAt: `${patch.patchedOn}T00:00:00Z`,
        body: `${patch.title}의 변경 내역`,
        bodyFormat: "PLAIN_TEXT",
        url: null,
      },
    }))
  }
  await page.goto("/games/7/reaction-trends")
  await expect(page.getByText("패치 2의 변경 내역", { exact: true })).toBeVisible()
  await page.getByRole("combobox", { name: "패치 시점" }).selectOption("patch-1")
  await expect(page.getByText("패치 1의 변경 내역", { exact: true })).toBeVisible()
  await expect(page.getByText("패치 2의 변경 내역", { exact: true })).toBeHidden()
})

test("리뷰 추가 로딩 실패 후 기존 원문을 유지하며 재시도한다", async ({ page, api }) => {
  let failed = true
  api.set("GET /games/7/reviews", (request) => {
    if (new URL(request.url()).searchParams.has("cursor")) {
      return failed
        ? { status: 503, message: "리뷰 추가 조회 실패" }
        : { data: { meta, ...list([reviews[1]], null, 2) } }
    }
    return { data: { meta, ...list([reviews[0]], "next", 2) } }
  })
  await page.goto("/games/7/reviews")
  await page.getByText(reviews[0].body, { exact: true }).scrollIntoViewIfNeeded()
  await page.mouse.wheel(0, 2000)
  await expect(page.getByRole("alert")).toBeVisible({ timeout: 15000 })
  await expect(page.getByText(reviews[0].body, { exact: true })).toHaveCount(1)
  failed = false
  await page.getByRole("button", { name: "다시 시도", exact: true }).click()
  await expect(page.getByText(reviews[1].body, { exact: true })).toBeVisible()
  await expect(page.getByText(reviews[0].body, { exact: true })).toHaveCount(1)
})
