import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { test } from "node:test"

const script = fileURLToPath(new URL("./commit-message.mjs", import.meta.url))
const cwd = mkdtempSync(join(tmpdir(), "thispatch-commit-message-"))
execFileSync("git", ["init", "--initial-branch=feat/#S15P21A202-73/example", cwd])
execFileSync("git", ["config", "thispatch.commitRole", "FE"], { cwd })

const cases = [
  ["chore: 코드 정렬", "chore: [S15P21A202-73] [FE] 코드 정렬"],
  ["코드 정렬", "feat: [S15P21A202-73] [FE] 코드 정렬"],
  ["Fix: 오류 수정", "fix: [S15P21A202-73] [FE] 오류 수정"],
  ["docs: [S15P21A202-20] 문서 수정", "docs: [S15P21A202-20] [FE] 문서 수정"],
  ["fix: [BE] 오류 수정", "fix: [S15P21A202-73] [BE] 오류 수정"],
  ["fix: [S15P21A202-20] [BE] 오류 수정", "fix: [S15P21A202-20] [BE] 오류 수정"],
  ["Merge branch 'develop'", "Merge branch 'develop'"],
  ['Revert "example"', 'Revert "example"'],
]

for (const [index, [input, expected]] of cases.entries()) {
  test(`normalize ${input}, preserve body, and remain idempotent`, () => {
    const messagePath = join(cwd, `message-${index}`)
    const body = "\n\n상세 설명\n\nCo-authored-by: Example <example@example.com>\n"
    writeFileSync(messagePath, input + body)
    for (const command of ["prepare", "validate", "prepare"]) {
      execFileSync(process.execPath, [script, command, messagePath], { cwd })
      assert.equal(readFileSync(messagePath, "utf8"), expected + body)
    }
  })
}

test("reject an empty subject without modifying the message", () => {
  const messagePath = join(cwd, "empty-message")
  writeFileSync(messagePath, "chore: ")
  assert.throws(() =>
    execFileSync(process.execPath, [script, "prepare", messagePath], { cwd, stdio: "pipe" }),
  )
  assert.equal(readFileSync(messagePath, "utf8"), "chore: ")
})
