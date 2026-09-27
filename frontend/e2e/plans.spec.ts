import { test, expect } from "./fixtures/test"
import { plan, savedPlan, list, similarCase } from "./fixtures/data"
import type { Page } from "@playwright/test"

async function structurePlan(page: Page) {
  await page.goto("/games/7/plan")
  await page.getByLabel("기획안 본문").fill(plan.rawText)
  await page.getByRole("button", { name: "변경점 구조화", exact: true }).click()
  await expect(page.getByRole("heading", { name: "변경 슬롯 추출" })).toBeVisible()
}

test("기획안 구조화와 슬롯 수정 후 선택한 장르로 사례 상세까지 확인한다", async ({ page }) => {
  await structurePlan(page)
  await page.getByRole("button", { name: /^장르 필터/ }).click()
  await page.getByRole("checkbox", { name: "로그라이크" }).uncheck()
  await page.getByRole("button", { name: /^장르 필터/ }).press("Escape")
  await page.getByRole("button", { name: "슬롯 직접 수정" }).click()
  await page.getByLabel("슬롯 1 변경 폭").fill("+30%")
  await page.getByRole("button", { name: "저장", exact: true }).click()
  await expect(page.getByText("INCREASE +30%", { exact: true })).toBeVisible()
  const searchRequest = page.waitForRequest((request) => request.url().endsWith("/case-searches"))
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  expect((await searchRequest).postDataJSON()).toMatchObject({
    planId: 101,
    genreIds: [2],
    confirmedSlots: [expect.objectContaining({ magnitude: "+30%" })],
  })
  await page.getByRole("link", { name: "다른 게임 사례 상세 비교" }).click()
  await expect(page).toHaveURL(/\/games\/7\/cases\/patch-8$/)
  await expect(page.getByText("Enemy health increased by 20%.", { exact: true })).toBeVisible()
  await expect(page.getByText("적의 체력을 높였습니다.", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: "사례 목록", exact: true }).click()
  await expect(page.getByRole("link", { name: "다른 게임 사례 상세 비교" })).toBeVisible()
})

test("사례 검색에서 돌아오면 본문과 수정 슬롯 및 장르가 유지된다", async ({ page }) => {
  await structurePlan(page)
  await page.getByRole("button", { name: "슬롯 직접 수정" }).click()
  await page.getByLabel("슬롯 1 적용 범위").fill("고난도")
  await page.getByRole("button", { name: "저장", exact: true }).click()
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  await expect(page.getByRole("link", { name: "다른 게임 사례 상세 비교" })).toBeVisible()
  await page.goBack()
  await expect(page.getByLabel("기획안 본문")).toHaveValue(plan.rawText)
  await expect(page.getByText("고난도", { exact: true })).toBeVisible()
  await expect(page.getByRole("button", { name: /^장르 필터/ })).toContainText("2 / 2")
})

test("게임별 초안은 섞이지 않고 브라우저 이동 시 복원된다", async ({ page }) => {
  await structurePlan(page)
  await page.getByRole("link", { name: "게임 분석", exact: true }).click()
  await page.getByRole("link", { name: "게임 목록", exact: true }).click()
  await page.getByRole("link", { name: "다른 게임", exact: true }).click()
  await page.getByRole("link", { name: /기획안 입력/ }).click()
  await expect(page.getByLabel("기획안 본문")).toHaveValue("")
  await page.goBack()
  await page.goBack()
  await page.goBack()
  await page.goBack()
  await expect(page.getByLabel("기획안 본문")).toHaveValue(plan.rawText)
})

test("빈 값과 공백 제출을 막고 기획안은 500자까지 입력된다", async ({ page }) => {
  await page.goto("/games/7/plan")
  const input = page.getByLabel("기획안 본문")
  const submit = page.getByRole("button", { name: "변경점 구조화", exact: true })
  await expect(submit).toBeDisabled()
  await input.fill("   ")
  await expect(submit).toBeDisabled()
  await input.fill("가".repeat(501))
  await expect(input).toHaveValue("가".repeat(500))
  await expect(page.getByText("500 / 500자", { exact: true })).toBeVisible()
})

test("구조화 중 중복 제출을 막고 실패 후 본문을 유지한다", async ({ page, api }) => {
  const gate = Promise.withResolvers<void>()
  let requests = 0
  api.set("POST /games/7/plan-structures", async () => {
    requests++
    await gate.promise
    return { status: 503, message: "구조화 실패" }
  })
  try {
    await page.goto("/games/7/plan")
    await page.getByLabel("기획안 본문").fill(plan.rawText)
    await page.getByRole("button", { name: "변경점 구조화" }).click()
    await expect(page.getByRole("button", { name: "구조화 중…" })).toBeDisabled()
    gate.resolve()
    await expect(page.getByRole("alert")).toHaveText("구조화 실패")
    await expect(page.getByLabel("기획안 본문")).toHaveValue(plan.rawText)
    await expect(page.getByRole("button", { name: "변경점 구조화" })).toBeEnabled()
    expect(requests).toBe(1)
  } finally {
    gate.resolve()
  }
})

test("슬롯 편집 취소는 기존 값을 유지하고 빈 추가 슬롯은 저장하지 않는다", async ({ page }) => {
  await structurePlan(page)
  await page.getByRole("button", { name: "슬롯 직접 수정" }).click()
  await page.getByLabel("슬롯 1 변경 폭").fill("+99%")
  await page.getByRole("button", { name: "취소", exact: true }).click()
  await expect(page.getByText("INCREASE +20%", { exact: true })).toBeVisible()
  await expect(page.getByText("INCREASE +99%", { exact: true })).toBeHidden()
  await page.getByRole("button", { name: "슬롯 직접 수정" }).click()
  await page.getByRole("button", { name: "저장", exact: true }).click()
  await expect(page.getByText("1개 슬롯", { exact: true })).toBeVisible()
})

test("새 슬롯을 추가하고 저장하면 검색 조건에 포함된다", async ({ page }) => {
  await structurePlan(page)
  await page.getByRole("button", { name: "슬롯 직접 수정" }).click()
  await page.getByLabel("슬롯 2 대상 이름").fill("Shieldbot")
  await page.getByLabel("슬롯 2 속성").fill("방어력")
  await page.getByRole("button", { name: "저장", exact: true }).click()
  await expect(page.getByText("2개 슬롯", { exact: true })).toBeVisible()
  const request = page.waitForRequest((request) => request.url().endsWith("/case-searches"))
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  expect((await request).postDataJSON().confirmedSlots).toHaveLength(2)
  await expect(page.getByRole("link", { name: "다른 게임 사례 상세 비교" })).toBeVisible()
})

for (const count of [0, 21]) {
  test(`변경 슬롯 ${count}개인 결과는 사례 검색을 막는다`, async ({ page, api }) => {
    api.set("POST /games/7/plan-structures", () => ({
      data: {
        ...plan,
        slots: Array.from({ length: count }, (_, index) => ({ ...plan.slots[0], id: index + 1 })),
      },
    }))
    await structurePlan(page)
    await expect(page.getByRole("button", { name: "유사 사례 검색", exact: true })).toBeDisabled()
  })
}

test("사례 정렬을 변경해도 기획안과 장르 조건을 유지한다", async ({ page, api }) => {
  const original = api.get("POST /games/7/case-searches")!
  api.set("POST /games/7/case-searches", async (request) => {
    const result = await original(request)
    return {
      data: {
        ...(result.data as object),
        groups: [
          {
            outcome: "POSITIVE_SHIFT",
            name: "긍정 급변",
            caseCount: 1,
            observedPatterns: [],
            cases: [{ ...similarCase, patchTitle: request.postDataJSON().sort }],
          },
        ],
      },
    }
  })
  await structurePlan(page)
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  for (const sort of ["REVIEW_COUNT_DESC", "PATCHED_ON_DESC"]) {
    const request = page.waitForRequest(
      (request) => request.url().endsWith("/case-searches") && request.postDataJSON().sort === sort,
    )
    await page.getByRole("combobox").selectOption(sort)
    expect((await request).postDataJSON()).toMatchObject({ planId: 101, genreIds: [1, 2], sort })
    await expect(page.getByText(new RegExp(`${sort} ·`))).toBeVisible()
  }
  // Returning to a fresh cached query need not issue another HTTP request.
  await page.getByRole("combobox").selectOption("SIMILARITY_DESC")
  await expect(page.getByText(/SIMILARITY_DESC ·/)).toBeVisible()
  await expect(page).not.toHaveURL(/sort=/)
})

for (const [path, message] of [
  ["/games/7/cases", "검색할 기획안이 없습니다."],
  ["/games/7/cases/patch-8", "비교할 사례 정보가 없습니다."],
]) {
  test(`상태 없는 직접 접근 ${path}을 안내한다`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByText(message, { exact: true })).toBeVisible()
    await expect(page.getByRole("link", { name: "기획안 입력으로 이동" })).toBeVisible()
  })
}

test("기획안 내역에서 선택한 원문과 변경점으로 다시 검색한다", async ({ page }) => {
  await page.goto("/plans?planId=101")
  await expect(page.getByText(savedPlan.rawText, { exact: true }).last()).toBeVisible()
  await expect(page.getByRole("table").getByText("HP", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: "이 기획안으로 유사 사례 검색" }).click()
  await expect(page).toHaveURL(/\/games\/7\/cases$/)
  await expect(page.getByRole("link", { name: "다른 게임 사례 상세 비교" })).toBeVisible()
})

test("기획안 내역 선택과 URL이 일치하고 상세 404 후 다른 항목을 선택한다", async ({
  page,
  api,
}) => {
  api.set("GET /members/me/patch-plans", () => ({
    data: list(
      [101, 102].map((id) => ({
        ...savedPlan,
        planId: id,
        rawTextPreview: `기획안 ${id}`,
        slotCount: 1,
        unknownEntityCount: 0,
      })),
    ),
  }))
  api.set("GET /members/me/patch-plans/102", () => ({ status: 404, message: "없음" }))
  await page.goto("/plans")
  await page.getByRole("button", { name: /기획안 102/ }).click()
  await expect(page).toHaveURL(/planId=102/)
  await expect(page.getByRole("alert")).toContainText("기획안 내역을 찾을 수 없습니다.")
  await page.getByRole("button", { name: /기획안 101/ }).click()
  await expect(page).toHaveURL(/planId=101/)
  await expect(page.getByRole("table")).toBeVisible()
  await expect(page.getByRole("alert")).toBeHidden()
})

test("기획안 내역이 없으면 입력을 시작할 경로를 표시한다", async ({ page, api }) => {
  api.set("GET /members/me/patch-plans", () => ({ data: list([]) }))
  await page.goto("/plans")
  await expect(page.getByText("아직 입력한 기획안이 없습니다", { exact: true })).toBeVisible()
  await page.getByRole("link", { name: "기획안 입력", exact: true }).click()
  await expect(page).toHaveURL(/\/my-games$/)
})

test("사례 검색 실패 후 같은 조건으로 재시도할 수 있다", async ({ page, api }) => {
  const original = api.get("POST /games/7/case-searches")!
  api.set("POST /games/7/case-searches", () => ({ status: 503, message: "사례 검색 실패" }))
  await structurePlan(page)
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  await expect(page.getByRole("alert")).toContainText("사례 검색 실패", { timeout: 15000 })
  api.set("POST /games/7/case-searches", original)
  await page.getByRole("button", { name: "다시 시도", exact: true }).click()
  await expect(page.getByRole("link", { name: "다른 게임 사례 상세 비교" })).toBeVisible()
  await expect(page.getByRole("alert")).toBeHidden()
})

test("사례 검색 결과가 없으면 기획안을 유지한 채 조건을 수정한다", async ({ page, api }) => {
  api.set("POST /games/7/case-searches", (request) => ({
    data: {
      ...request.postDataJSON(),
      gameId: 7,
      status: "COMPLETED",
      totalCount: 0,
      groups: [],
      notices: [],
    },
  }))
  await structurePlan(page)
  await page.getByRole("button", { name: "유사 사례 검색", exact: true }).click()
  await expect(
    page.getByRole("heading", { name: "현재 조건과 맞는 사례가 없습니다" }),
  ).toBeVisible()
  await page.getByRole("link", { name: "검색 조건 수정", exact: true }).click()
  await expect(page.getByLabel("기획안 본문")).toHaveValue(plan.rawText)
  await expect(page.getByText("INCREASE +20%", { exact: true })).toBeVisible()
})

test("기획안 내역을 추가로 불러온 뒤 선택한 내역의 상세를 표시한다", async ({ page, api }) => {
  const items = Array.from({ length: 12 }, (_, index) => ({
    ...savedPlan,
    planId: 101 + index,
    rawTextPreview: `내역 ${101 + index}`,
    slotCount: 1,
    unknownEntityCount: 0,
  }))
  api.set("GET /members/me/patch-plans", (request) => ({
    data: new URL(request.url()).searchParams.has("cursor")
      ? list([{ ...items[0], planId: 201, rawTextPreview: "추가 기획안" }], null, 13)
      : list(items, "next", 13),
  }))
  api.set("GET /members/me/patch-plans/201", () => ({
    data: { ...savedPlan, planId: 201, rawText: "추가 기획안의 원문" },
  }))
  await page.goto("/plans")
  await page.getByRole("button", { name: /내역 112/ }).scrollIntoViewIfNeeded()
  await page.mouse.wheel(0, 1500)
  await page.getByRole("button", { name: /추가 기획안/ }).click()
  await expect(page).toHaveURL(/planId=201/)
  await expect(page.getByText("추가 기획안의 원문", { exact: true })).toBeVisible()
  await expect(page.getByRole("button", { name: /내역 101/ })).toHaveCount(1)
})

test("확정 변경점이 없는 기획안은 재검색을 막는다", async ({ page, api }) => {
  api.set("GET /members/me/patch-plans/101", () => ({ data: { ...savedPlan, confirmedSlots: [] } }))
  await page.goto("/plans")
  await expect(page.getByRole("button", { name: "이 기획안으로 유사 사례 검색" })).toBeDisabled()
  await expect(page.getByText(/확정된 변경점이 없는 검색 내역/)).toBeVisible()
})
