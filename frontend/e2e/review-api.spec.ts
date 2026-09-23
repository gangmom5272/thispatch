import { expect, test, type Page } from "@playwright/test"

const period = { startDate: "2026-09-04", endDate: "2026-09-17", dayCount: 14 }
const meta = {
  period,
  timezone: "Asia/Seoul",
  aggregationBasis: "UPDATED_AT",
  lastCollectedAt: null,
  dataStatus: "AVAILABLE",
}
const unavailable = {
  status: "UNAVAILABLE",
  text: null,
  targetPeriod: period,
  reasonCode: "AI_UNAVAILABLE",
  message: "AI 요약을 일시적으로 이용할 수 없습니다.",
}
const reviews = Array.from({ length: 4 }, (_, index) => ({
  id: index + 1,
  sentiment: "POSITIVE",
  isUpdated: false,
  playtimeMinutes: null,
  languageCode: "korean",
  helpfulCount: 100 - index,
  tags: [],
  body: `대표 리뷰 원문 ${index + 1}`,
  reviewDate: "2026-09-17",
}))

async function mockApi(page: Page, responses: Record<string, unknown>) {
  await page.addInitScript(() => localStorage.setItem("thispatch.accessToken", "test-token"))
  const defaults: Record<string, unknown> = {
    "/session": { authenticated: true, user: { id: 1, nickname: "테스트" } },
    "/games/7": {
      id: 7,
      title: "테스트 게임",
      capsuleImageUrl: null,
      tags: [],
      positiveRate: 80,
      isMine: false,
      description: "게임",
      releasedOn: "2026-01-01",
      reviewCount: 40,
      lastCollectedAt: null,
    },
    ...responses,
  }
  await page.route("**/api/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname
    // Vite의 /src/api/*.ts 모듈 요청은 가로채지 않는다.
    if (!pathname.startsWith("/api/")) {
      await route.continue()
      return
    }
    const path = pathname.slice("/api".length)
    if (!(path in defaults)) {
      await route.fulfill({
        status: 404,
        json: {
          code: "NOT_FOUND",
          message: "테스트 응답 없음",
          responsedAt: "2026-09-17 10:00:00",
        },
      })
      return
    }
    await route.fulfill({
      json: {
        code: "200",
        message: "성공했습니다.",
        responsedAt: "2026-09-17 10:00:00",
        success: true,
        data: defaults[path],
      },
    })
  })
}

test("대표 리뷰 객체 응답의 items 네 건과 플레이타임 결측을 표시한다", async ({ page }) => {
  await mockApi(page, {
    "/games/7/reviews/representative": { meta, items: reviews },
    "/games/7/reviews": {
      meta,
      items: [],
      page: { limit: 10, nextCursor: null, hasNext: false, totalCount: 0 },
    },
  })
  await page.goto("/games/7/reviews")
  for (const review of reviews)
    await expect(page.getByText(review.body, { exact: true })).toBeVisible()
  await expect(page.getByText(/플레이타임 정보 없음/)).toHaveCount(4)
})

test("언어 요약이 이용 불가여도 표본 충족과 대표 리뷰 네 건을 유지한다", async ({ page }) => {
  await mockApi(page, {
    "/games/7/language-analysis": {
      meta,
      totalReviewCount: 40,
      isSufficientSample: true,
      minimumSampleCount: 30,
      languages: [
        {
          languageCode: "korean",
          displayName: "한국어",
          reviewCount: 40,
          reviewShare: 100,
          positiveRate: 80,
          positiveCount: 32,
          negativeCount: 8,
          isSufficientSample: true,
        },
      ],
    },
    "/games/7/language-analysis/korean": {
      meta,
      languageCode: "korean",
      summary: { ...unavailable, targetReviewCount: 40, usedReviewCount: null, selection: null },
      representativeReviews: reviews,
    },
  })
  await page.goto("/games/7/language-analysis")
  await expect(page.getByText(/✓ 표본 충족/)).toBeVisible()
  await page.getByRole("button", { name: /한국어/ }).click()
  await expect(page.getByText(unavailable.message, { exact: true })).toBeVisible()
  for (const review of reviews)
    await expect(page.getByText(review.body, { exact: true })).toBeVisible()
  await expect(page.getByRole("button", { name: "번역", exact: true }).first()).toBeDisabled()
  await page.getByRole("button", { name: "원문", exact: true }).first().click()
  await expect(page.getByText(reviews[0].body, { exact: true })).toBeVisible()
})

test("isSufficientSample로 토픽 집계를 표시하고 AI 장애를 구분한다", async ({ page }) => {
  const band = {
    minMinutes: 0,
    maxMinutesExclusive: null,
    reviewCount: 40,
    classifiedReviewCount: 30,
    positiveCount: 32,
    negativeCount: 8,
    positiveRate: 80,
    isSufficientSample: true,
  }
  await mockApi(page, {
    "/games/7/playtime-topics": {
      meta,
      selectedBand: "ALL",
      minimumSampleCount: 30,
      isSufficientSample: true,
      scale: {
        source: "ALL_GAME_REVIEWS",
        sampleCount: 160,
        p25Minutes: 60,
        medianMinutes: 120,
        p75Minutes: 180,
      },
      overall: { ...band, band: "ALL" },
      bands: [1, 2, 3, 4].map((number) => ({
        ...band,
        band: `B${number}`,
        minMinutes: (number - 1) * 60,
        maxMinutesExclusive: number === 4 ? null : number * 60,
      })),
      topics: [],
      fallback: null,
    },
    "/games/7/summaries/playtime-topics": {
      meta,
      selectedBand: "ALL",
      summary: {
        ...unavailable,
        targetReviewCount: 40,
        usedReviewCount: null,
        selection: null,
        recurringExpressions: [],
      },
    },
  })
  await page.goto("/games/7/playtime-topics")
  await expect(page.getByText("AI 대표 반응 요약", { exact: true })).toBeVisible()
  await expect(page.getByText(unavailable.message, { exact: true })).toBeVisible()
})

test("집계 이력이 없어도 반응 추세 화면과 AI 이용 불가 안내를 유지한다", async ({ page }) => {
  const counts = {
    reviewCount: 0,
    positiveCount: 0,
    negativeCount: 0,
    positiveRate: null,
    firstWrittenCount: 0,
    updatedCount: 0,
    firstWrittenPositiveCount: 0,
    firstWrittenNegativeCount: 0,
    updatedPositiveCount: 0,
    updatedNegativeCount: 0,
  }
  await mockApi(page, {
    "/games/7/reaction-trends": {
      meta,
      availablePeriod: null,
      summary: { ...counts, firstWrittenPositiveRate: null, updatedPositiveRate: null },
      daily: [{ ...counts, date: "2026-09-17", dataAvailable: true, patches: [] }],
    },
    "/games/7/summaries/reaction-trends": { meta, summary: unavailable },
  })
  await page.goto("/games/7/reaction-trends")
  await expect(page.getByText(unavailable.message, { exact: true })).toBeVisible()
  await expect(page.getByText("Unexpected Application Error!")).toHaveCount(0)
})

test("집계 마지막 날 이후 일자는 반응 추세 차트에서 제외한다", async ({ page }) => {
  const day = (date: string, reviewCount: number) => ({
    date,
    dataAvailable: true,
    reviewCount,
    positiveCount: reviewCount,
    negativeCount: 0,
    positiveRate: 100,
    firstWrittenCount: reviewCount,
    updatedCount: 0,
    firstWrittenPositiveCount: reviewCount,
    firstWrittenNegativeCount: 0,
    updatedPositiveCount: 0,
    updatedNegativeCount: 0,
    patches: [],
  })
  await mockApi(page, {
    "/games/7/reaction-trends": {
      meta,
      availablePeriod: null,
      summary: {
        reviewCount: 530,
        positiveCount: 530,
        negativeCount: 0,
        positiveRate: 100,
        firstWrittenCount: 530,
        firstWrittenPositiveCount: 530,
        firstWrittenNegativeCount: 0,
        firstWrittenPositiveRate: 100,
        updatedCount: 0,
        updatedPositiveCount: 0,
        updatedNegativeCount: 0,
        updatedPositiveRate: null,
      },
      daily: [day("2026-09-22", 10), day("2026-09-23", 20), day("2026-09-24", 500)],
    },
    "/games/7/summaries/reaction-trends": { meta, summary: unavailable },
  })
  await page.goto("/games/7/reaction-trends")
  await expect(page.getByText("09-23")).toBeVisible()
  await expect(page.getByText("09-24")).toHaveCount(0)
  await expect(page.getByRole("paragraph").filter({ hasText: /^30건$/ })).toBeVisible()
})
