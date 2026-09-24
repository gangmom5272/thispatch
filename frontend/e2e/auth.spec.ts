import { test, expect } from "./fixtures/test"
import { account } from "./fixtures/data"
import type { Page } from "@playwright/test"

async function loginThroughUi(page: Page, email = account.email, password = account.password) {
  await page.getByLabel("이메일").fill(email)
  await page.getByLabel("비밀번호", { exact: true }).fill(password)
  await page.getByRole("button", { name: "로그인", exact: true }).click()
}

test.describe("로그인 전", () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test("회원가입 후 가입한 계정으로 로그인한다", async ({ page }) => {
    await page.goto("/signup")
    await page.getByLabel("이메일").fill("new@example.com")
    await page.getByLabel("비밀번호", { exact: true }).fill(account.password)
    await page.getByLabel("닉네임").fill("신규회원")
    await page.getByRole("button", { name: "회원가입", exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    await loginThroughUi(page, "new@example.com")
    await expect(page).toHaveURL(/\/games$/)
    await expect(page.getByRole("heading", { name: "게임 탐색" })).toBeVisible()
  })
  test("로그인 후 새로고침해도 인증 상태를 유지한다", async ({ page }) => {
    await page.goto("/login")
    await loginThroughUi(page)
    await expect(page).toHaveURL(/\/games$/)
    await page.reload()
    await expect(page.getByRole("heading", { name: "게임 탐색" })).toBeVisible()
    await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible()
  })
  for (const path of [
    "/games?search=test&genreIds=1",
    "/games/7/reaction-trends",
    "/my-games",
    "/plans",
    "/mypage",
    "/games/7/plan",
  ]) {
    test(`비로그인 접근 ${path}은 로그인 후 원래 주소로 복귀한다`, async ({ page }) => {
      const messages: string[] = []
      page.on("dialog", async (dialog) => {
        messages.push(dialog.message())
        await dialog.accept()
      })
      await page.goto(path)
      await expect(page).toHaveURL(/\/login$/)
      expect(messages).toEqual(["로그인 후 이용 가능합니다"])
      await expect(page.getByRole("button", { name: "로그아웃" })).toBeHidden()
      await loginThroughUi(page)
      await expect(page).toHaveURL(`http://localhost:5174${path}`)
      await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible()
    })
  }
  test("잘못된 비밀번호 오류를 확인하고 다시 로그인한다", async ({ page }) => {
    await page.goto("/login")
    await loginThroughUi(page, account.email, "wrong")
    await expect(page.getByText("이메일 또는 비밀번호가 일치하지 않습니다.")).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
    await loginThroughUi(page)
    await expect(page).toHaveURL(/\/games$/)
  })
  test("로그인 요청 실패를 안내하고 재제출할 수 있다", async ({ page, api }) => {
    const original = api.get("POST /auth/login")!
    api.set("POST /auth/login", () => ({
      status: 503,
      message: "로그인 서버를 사용할 수 없습니다.",
    }))
    await page.goto("/login")
    await loginThroughUi(page)
    await expect(page.getByText("로그인 서버를 사용할 수 없습니다.")).toBeVisible()
    api.set("POST /auth/login", original)
    await loginThroughUi(page)
    await expect(page).toHaveURL(/\/games$/)
  })
  test("로그인 처리 중에는 중복 제출을 막는다", async ({ page, api }) => {
    const gate = Promise.withResolvers<void>()
    const original = api.get("POST /auth/login")!
    let requests = 0
    api.set("POST /auth/login", async (request) => {
      requests++
      await gate.promise
      return original(request)
    })
    try {
      await page.goto("/login")
      await loginThroughUi(page)
      await expect(page.getByRole("button", { name: "로그인 중…" })).toBeDisabled()
      await expect(page.getByLabel("이메일")).toBeDisabled()
      gate.resolve()
      await expect(page).toHaveURL(/\/games$/)
      expect(requests).toBe(1)
    } finally {
      gate.resolve()
    }
  })
})

test("로그아웃 후 보호 화면에 다시 접근할 수 없다", async ({ page }) => {
  page.on("dialog", (dialog) => dialog.accept())
  await page.goto("/games")
  await page.getByRole("button", { name: "로그아웃" }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto("/my-games")
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole("heading", { name: "내 게임", exact: true })).toBeHidden()
})
test("로그인한 사용자의 홈 접근은 게임 탐색으로 이동한다", async ({ page }) => {
  await page.goto("/")
  await expect(page).toHaveURL(/\/games$/)
  await expect(page.getByRole("heading", { name: "게임 탐색" })).toBeVisible()
})

test("세션 확인이 실패하면 보호 화면 대신 복구 안내를 표시한다", async ({ page, api }) => {
  api.set("GET /session", () => ({ status: 503, message: "세션을 확인할 수 없습니다." }))
  await page.goto("/games")
  await expect(page.getByRole("alert")).toContainText("세션", { timeout: 15000 })
  await expect(page.getByRole("heading", { name: "게임 탐색" })).toBeHidden()
})
