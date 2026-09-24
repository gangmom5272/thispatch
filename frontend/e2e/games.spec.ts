import { test, expect } from "./fixtures/test"
import { games, list } from "./fixtures/data"

test("게임 카드를 선택하면 해당 게임의 기본 분석 탭을 연다", async ({ page }) => {
  await page.goto("/games")
  await page.getByRole("link", { name: "테스트 게임", exact: true }).click()
  await expect(page).toHaveURL(/\/games\/7\/reaction-trends$/)
  await expect(page.getByRole("heading", { name: "테스트 게임", exact: true })).toBeVisible()
})

test("검색어와 결과가 새로고침 및 뒤로가기에서도 일치한다", async ({ page }) => {
  await page.goto("/games")
  await page.getByRole("combobox").fill("테스트")
  await page.getByRole("button", { name: "검색", exact: true }).click()
  await expect(page).toHaveURL(/search=/)
  await expect(page.getByRole("link", { name: "테스트 게임", exact: true })).toBeVisible()
  await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeHidden()
  await page.reload()
  await expect(page.getByRole("combobox")).toHaveValue("테스트")
  await page.getByRole("combobox").fill("다른")
  await page.getByRole("button", { name: "검색", exact: true }).click()
  await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole("combobox")).toHaveValue("테스트")
  await expect(page.getByRole("link", { name: "테스트 게임", exact: true })).toBeVisible()
})

test("필터 적용 즉시 URL과 결과를 갱신하고 상세에서 목록으로 복귀한다", async ({ page }) => {
  await page.goto("/games")
  await page.getByRole("button", { name: /^필터/ }).click()
  const dialog = page.getByRole("dialog")
  await dialog.getByRole("combobox", { name: "장르 입력" }).fill("액션")
  await dialog.getByRole("option", { name: /^액션/ }).click()
  await dialog.getByLabel("개발사", { exact: true }).fill("Studio A")
  await dialog
    .getByRole("group", { name: /출시연도/ })
    .getByLabel("최소")
    .fill("2020")
  await dialog
    .getByRole("group", { name: /긍정률/ })
    .getByLabel("최대")
    .fill("90")
  await dialog.getByRole("radio", { name: "긍정률 낮은 순" }).check()
  await dialog.getByRole("button", { name: "적용", exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/developer=Studio\+A/)
  await expect(page).toHaveURL(/genreIds=2/)
  await expect(page).toHaveURL(/releaseYearFrom=2020/)
  await expect(page).toHaveURL(/maxPositiveRate=90/)
  await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeHidden()
  const filteredUrl = page.url()
  await page.getByRole("link", { name: "테스트 게임", exact: true }).click()
  await page.getByRole("link", { name: "게임 목록", exact: true }).click()
  await expect(page).toHaveURL(filteredUrl)
  await expect(page.getByRole("button", { name: "액션 장르 조건 제거" })).toBeVisible()
})

test("필터 편집 취소는 기존 조건을 유지한다", async ({ page }) => {
  await page.goto("/games?developer=Studio+A")
  await page.getByRole("button", { name: /^필터/ }).click()
  await page.getByRole("dialog").getByLabel("개발사", { exact: true }).fill("Studio B")
  await page.getByRole("dialog").getByRole("button", { name: "취소" }).click()
  await expect(page).toHaveURL(/developer=Studio\+A$/)
  await expect(page.getByRole("link", { name: "테스트 게임", exact: true })).toBeVisible()
  await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeHidden()
})

test("조건을 개별 제거하고 초기화해도 검색어는 유지한다", async ({ page }) => {
  await page.goto("/games?search=게임&genreIds=2&developer=Studio+A&sort=POSITIVE_RATE_ASC")
  await page.getByRole("button", { name: "액션 장르 조건 제거" }).click()
  await expect(page).not.toHaveURL(/genreIds=/)
  await expect(page).toHaveURL(/developer=/)
  await page.getByRole("button", { name: "초기화", exact: true }).click()
  await expect(page).not.toHaveURL(/developer=|sort=/)
  await expect(page.getByRole("combobox")).toHaveValue("게임")
  await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeVisible()
})

test("최소가 최대보다 큰 범위는 적용할 수 없다", async ({ page }) => {
  await page.goto("/games")
  await page.getByRole("button", { name: /^필터/ }).click()
  const group = page.getByRole("dialog").getByRole("group", { name: /긍정률/ })
  await group.getByLabel("최소").fill("90")
  await group.getByLabel("최대").fill("20")
  await page.getByRole("button", { name: "적용", exact: true }).click()
  await expect(page.getByRole("dialog")).toBeVisible()
  await expect(page).toHaveURL(/\/games$/)
})

test("검색 결과가 없으면 빈 상태를 안내한다", async ({ page }) => {
  await page.goto("/games?search=존재하지않는게임")
  await expect(page.getByRole("link", { name: "테스트 게임", exact: true })).toBeHidden()
  await expect(
    page.getByText(/검색 결과가 없습니다|조건에 맞는 게임이 없습니다|검색된 게임이 없습니다/),
  ).toBeVisible()
})

test("내 게임 등록은 새로고침 후 유지되고 해제하면 빈 상태로 바뀐다", async ({ page }) => {
  await page.goto("/games")
  await page.getByRole("button", { name: "테스트 게임 내 게임으로 등록" }).click()
  await expect(page.getByRole("button", { name: "테스트 게임 내 게임 등록 해제" })).toBeVisible()
  await page.getByRole("link", { name: "내 게임", exact: true }).click()
  await expect(
    page
      .getByRole("list", { name: "내 게임", exact: true })
      .getByRole("link", { name: "테스트 게임", exact: true }),
  ).toBeVisible()
  await page.reload()
  await page.getByRole("button", { name: "테스트 게임 내 게임 등록 해제" }).click()
  await expect(page.getByText("등록한 게임이 없습니다", { exact: true })).toBeVisible()
  await expect(page.getByRole("link", { name: /게임 탐색으로 이동/ })).toBeVisible()
})

test("내 게임 등록 실패 시 별 상태를 되돌리고 오류를 표시한다", async ({ page, api }) => {
  api.set("POST /games/7/my-game", () => ({ status: 500, message: "등록에 실패했습니다." }))
  await page.goto("/games")
  await page.getByRole("button", { name: "테스트 게임 내 게임으로 등록" }).click()
  await expect(page.getByRole("alert")).toContainText("등록에 실패했습니다.")
  await expect(page.getByRole("button", { name: "테스트 게임 내 게임으로 등록" })).toBeEnabled()
})

for (const path of ["/games", "/my-games"]) {
  test(`${path} 스크롤로 다음 게임을 중복 없이 추가한다`, async ({ page, api }) => {
    const firstPage = Array.from({ length: 12 }, (_, index) => ({
      ...games[0],
      id: 100 + index,
      title: `목록 게임 ${index + 1}`,
    }))
    const endpoint = path === "/games" ? "/games" : "/members/me/games"
    api.set(`GET ${endpoint}`, (request) => ({
      data: new URL(request.url()).searchParams.has("cursor")
        ? list([games[1]], null, 13)
        : list(firstPage, "next", 13),
    }))
    await page.goto(path)
    await page.getByRole("link", { name: "목록 게임 12", exact: true }).scrollIntoViewIfNeeded()
    await page.mouse.wheel(0, 1800)
    await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeVisible()
    await expect(page.getByRole("link", { name: "목록 게임 1", exact: true })).toHaveCount(1)
    await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toHaveCount(1)
  })
}

for (const [path, endpoint, heading] of [
  ["/games", "/games", "게임 탐색"],
  ["/my-games", "/members/me/games", "내 게임"],
  ["/plans", "/members/me/patch-plans", "기획안 내역"],
]) {
  test(`${heading} 조회 실패 후 다시 시도하면 복구한다`, async ({ page, api }) => {
    const original = api.get(`GET ${endpoint}`)!
    api.set(`GET ${endpoint}`, () => ({ status: 503, message: "목록 조회 실패" }))
    await page.goto(path)
    await expect(page.getByRole("alert")).toContainText("목록 조회 실패", { timeout: 15000 })
    api.set(`GET ${endpoint}`, original)
    await page.getByRole("button", { name: "다시 시도", exact: true }).click()
    await expect(page.getByRole("alert")).toBeHidden()
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible()
  })
}

for (const path of ["/games", "/my-games"]) {
  test(`${path} 추가 로딩 실패 후 기존 목록을 유지하며 재시도한다`, async ({ page, api }) => {
    let failed = true
    const firstPage = Array.from({ length: 12 }, (_, index) => ({
      ...games[0],
      id: 100 + index,
      title: `첫 페이지 게임 ${index + 1}`,
    }))
    const endpoint = path === "/games" ? "/games" : "/members/me/games"
    api.set(`GET ${endpoint}`, (request) =>
      new URL(request.url()).searchParams.has("cursor")
        ? failed
          ? { status: 503, message: "다음 페이지 실패" }
          : { data: list([games[1]], null, 13) }
        : { data: list(firstPage, "next", 13) },
    )
    await page.goto(path)
    await page
      .getByRole("link", { name: "첫 페이지 게임 12", exact: true })
      .scrollIntoViewIfNeeded()
    await page.mouse.wheel(0, 2000)
    await expect(page.getByRole("alert")).toContainText(
      /다음 페이지 실패|다음 게임을 불러오지 못했습니다/,
      { timeout: 15000 },
    )
    await expect(page.getByRole("link", { name: "첫 페이지 게임 1", exact: true })).toHaveCount(1)
    failed = false
    await page.getByRole("button", { name: "다시 시도", exact: true }).click()
    await page.mouse.wheel(0, 2000)
    await expect(page.getByRole("link", { name: "다른 게임", exact: true })).toBeVisible()
    await expect(page.getByRole("link", { name: "첫 페이지 게임 1", exact: true })).toHaveCount(1)
  })
}
