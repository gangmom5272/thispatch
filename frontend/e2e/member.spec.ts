import { test, expect } from "./fixtures/test"
import { account } from "./fixtures/data"

test("닉네임 변경 후 새로고침해도 변경한 이름을 표시한다", async ({ page }) => {
  await page.goto("/mypage")
  await page.getByRole("textbox", { name: /^닉네임/ }).fill("변경한닉네임")
  await page.getByRole("button", { name: "닉네임 저장" }).click()
  await expect(page.getByRole("status")).toHaveText("닉네임을 변경했습니다.")
  await page.reload()
  await expect(page.getByRole("textbox", { name: /^닉네임/ })).toHaveValue("변경한닉네임")
})

for (const [nickname, message] of [
  ["   ", "닉네임은 공백만으로 설정할 수 없습니다."],
  [account.nickname, "현재 닉네임과 같습니다. 다른 닉네임을 입력해 주세요."],
]) {
  test(`닉네임 검증: ${message}`, async ({ page, api }) => {
    let mutations = 0
    api.set("PATCH /members/me/nickname", () => {
      mutations++
      return {}
    })
    await page.goto("/mypage")
    await page.getByRole("textbox", { name: /^닉네임/ }).fill(nickname)
    await page.getByRole("button", { name: "닉네임 저장" }).click()
    await expect(page.getByRole("alert")).toHaveText(message)
    expect(mutations).toBe(0)
  })
}

test("변경한 비밀번호로 다시 로그인한다", async ({ page }) => {
  page.on("dialog", (dialog) => dialog.accept())
  await page.goto("/mypage")
  await page.getByLabel("현재 비밀번호", { exact: true }).fill(account.password)
  await page.getByLabel(/^새 비밀번호\s*공백/).fill("Changed-password-123!")
  await page.getByLabel("새 비밀번호 확인", { exact: true }).fill("Changed-password-123!")
  await page.getByRole("button", { name: "비밀번호 변경", exact: true }).click()
  await expect(page.getByRole("status")).toContainText("비밀번호를 변경했습니다.")
  await page.getByRole("button", { name: "로그아웃" }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel("이메일").fill(account.email)
  await page.getByLabel("비밀번호", { exact: true }).fill("Changed-password-123!")
  await page.getByRole("button", { name: "로그인", exact: true }).click()
  await expect(page).toHaveURL(/\/mypage$/)
  await expect(page.getByRole("heading", { name: "마이페이지", exact: true })).toBeVisible()
})

for (const [current, password, confirmation, message] of [
  [account.password, "new-password", "different", "새 비밀번호 확인이 일치하지 않습니다."],
  [
    account.password,
    account.password,
    account.password,
    "현재 비밀번호와 다른 비밀번호를 입력해 주세요.",
  ],
  ["wrong", "new-password", "new-password", "현재 비밀번호가 일치하지 않습니다."],
  [account.password, "   ", "   ", "새 비밀번호는 공백만으로 설정할 수 없습니다."],
]) {
  test(`비밀번호 검증: ${message}`, async ({ page }) => {
    await page.goto("/mypage")
    await page.getByLabel("현재 비밀번호", { exact: true }).fill(current)
    await page.getByLabel(/^새 비밀번호\s*공백/).fill(password)
    await page.getByLabel("새 비밀번호 확인", { exact: true }).fill(confirmation)
    await page.getByRole("button", { name: "비밀번호 변경", exact: true }).click()
    await expect(page.getByRole("alert")).toHaveText(message)
  })
}

test("탈퇴 확인 문구가 맞아야 제출할 수 있고 취소하면 계정이 유지된다", async ({ page, api }) => {
  let mutations = 0
  api.set("DELETE /members/me", () => {
    mutations++
    return {}
  })
  await page.goto("/mypage")
  await page.getByRole("button", { name: "회원 탈퇴", exact: true }).click()
  const dialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "정말 탈퇴하시겠어요?" }) })
  await expect(dialog.getByRole("button", { name: "탈퇴하기" })).toBeDisabled()
  await dialog.getByRole("textbox").fill("탈퇴")
  await expect(dialog.getByRole("button", { name: "탈퇴하기" })).toBeDisabled()
  await dialog.getByRole("textbox").fill("탈퇴합니다")
  await expect(dialog.getByRole("button", { name: "탈퇴하기" })).toBeEnabled()
  await dialog.getByRole("button", { name: "취소" }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible()
  expect(mutations).toBe(0)
})

test("탈퇴 성공 후 홈으로 이동하고 보호 화면을 열 수 없다", async ({ page }) => {
  page.on("dialog", (dialog) => dialog.accept())
  await page.goto("/mypage")
  await page.getByRole("button", { name: "회원 탈퇴", exact: true }).click()
  const dialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "정말 탈퇴하시겠어요?" }) })
  await dialog.getByRole("textbox").fill("탈퇴합니다")
  await dialog.getByRole("button", { name: "탈퇴하기" }).click()
  await expect(page).toHaveURL("http://localhost:5174/")
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeHidden()
  await page.goto("/plans")
  await expect(page).toHaveURL(/\/login$/)
})

test("탈퇴 실패 시 모달에서 오류를 확인하고 다시 시도한다", async ({ page, api }) => {
  api.set("DELETE /members/me", () => ({ status: 500, message: "탈퇴 처리 실패" }))
  await page.goto("/mypage")
  await page.getByRole("button", { name: "회원 탈퇴", exact: true }).click()
  const dialog = page
    .getByRole("dialog")
    .filter({ has: page.getByRole("heading", { name: "정말 탈퇴하시겠어요?" }) })
  await dialog.getByRole("textbox").fill("탈퇴합니다")
  await dialog.getByRole("button", { name: "탈퇴하기" }).click()
  await expect(dialog.getByRole("alert")).toHaveText("탈퇴 처리 실패")
  await expect(dialog.getByRole("button", { name: "탈퇴하기" })).toBeEnabled()
  await expect(dialog.getByRole("textbox")).toHaveValue("탈퇴합니다")
})
