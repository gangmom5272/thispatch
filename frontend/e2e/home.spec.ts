import { test, expect } from "./fixtures/test"

test.use({ storageState: { cookies: [], origins: [] } })

test("비로그인 사용자는 랜딩에서 로그인 후 시작할 수 있다", async ({ page }) => {
  await page.goto("/")
  await page.getByRole("link", { name: "로그인 후 시작", exact: true }).first().click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel("이메일")).toBeVisible()
})

test("알 수 없는 주소에서는 복귀 가능한 오류 화면을 표시한다", async ({ page }) => {
  await page.goto("/does-not-exist")
  await expect(page.getByText("404", { exact: true })).toBeVisible()
  await page.getByRole("link", { name: "홈으로 돌아가기" }).click()
  await expect(page).toHaveURL("http://localhost:5174/")
})
