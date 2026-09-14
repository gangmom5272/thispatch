import { execFileSync } from "node:child_process"
import { readFileSync, writeFileSync } from "node:fs"

const COMMIT_TYPES = "feat|chore|docs|fix|test|refactor|build|hotfix"
const COMMIT_MESSAGE_PATTERN = new RegExp(
  `^(${COMMIT_TYPES}): \\[([^\\]]+)\\] \\[([^\\]]+)\\] (\\S.*)$`,
)

function runGit(args) {
  return execFileSync("git", args, { encoding: "utf8" }).trim()
}

function getBranchParts() {
  const branch = runGit(["branch", "--show-current"])
  const match = branch.match(new RegExp(`^(${COMMIT_TYPES})/#([A-Z][A-Z0-9]*-[0-9]+)/`))

  if (!match) {
    throw new Error(
      `현재 브랜치 '${branch}'에서 타입과 Jira 이슈 키를 찾을 수 없습니다. ` +
        "예: feat/#S15P11A503-38/user-register",
    )
  }

  return { type: match[1], issueKey: match[2] }
}

function getRole() {
  try {
    return runGit(["config", "--get", "thispatch.commitRole"]) || "FE"
  } catch {
    return "FE"
  }
}

function prepareMessage(messagePath) {
  const message = readFileSync(messagePath, "utf8")
  const firstLine = message.split("\n")[0]

  if (firstLine.startsWith("Merge ") || firstLine.startsWith("Revert ")) {
    return
  }

  if (COMMIT_MESSAGE_PATTERN.test(firstLine)) {
    return
  }

  const { type, issueKey } = getBranchParts()
  const role = getRole()
  const subject = firstLine.replace(new RegExp(`^${COMMIT_TYPES}:\\s*`), "").trim()

  if (!subject) {
    throw new Error("커밋 메시지를 입력해 주세요.")
  }

  const lines = message.split("\n")
  lines[0] = `${type}: [${issueKey}] [${role}] ${subject}`
  writeFileSync(messagePath, lines.join("\n"))
}

function validateMessage(messagePath, messageLabel = messagePath) {
  const firstLine = readFileSync(messagePath, "utf8").split("\n")[0]

  if (firstLine.startsWith("Merge ") || firstLine.startsWith("Revert ")) {
    return
  }

  if (!COMMIT_MESSAGE_PATTERN.test(firstLine)) {
    throw new Error(
      `${messageLabel}: '${firstLine}'\n` +
        "커밋 형식: {타입}: [{지라 이슈 키}] [{역할}] {커밋 메시지}\n" +
        "예시: feat: [S15P11A503-38] [FE] 회원가입 화면 구현",
    )
  }
}

function validatePush() {
  const input = process.stdin ? readStdin() : ""
  const refs = input.split("\n").filter(Boolean)
  const commits = new Set()

  for (const ref of refs) {
    const [localRef, localSha, remoteRef, remoteSha] = ref.split(" ")
    if (localSha === "0".repeat(40)) continue

    const range = remoteSha === "0".repeat(40) ? localSha : `${remoteSha}..${localSha}`
    const messages = runGit(["log", "--format=%H%x00%s", range]).split("\n").filter(Boolean)

    for (const entry of messages) {
      const separator = entry.indexOf("\0")
      const sha = entry.slice(0, separator)
      if (commits.has(sha)) continue
      commits.add(sha)
      const subject = entry.slice(separator + 1)
      if (
        !COMMIT_MESSAGE_PATTERN.test(subject) &&
        !subject.startsWith("Merge ") &&
        !subject.startsWith("Revert ")
      ) {
        throw new Error(`${sha}: '${subject}'\n푸시할 커밋 메시지가 규칙에 맞지 않습니다.`)
      }
    }
  }
}

function readStdin() {
  try {
    return readFileSync(0, "utf8")
  } catch {
    return ""
  }
}

try {
  const [command, messagePath] = process.argv.slice(2)
  if (command === "prepare") prepareMessage(messagePath)
  else if (command === "validate") validateMessage(messagePath)
  else if (command === "validate-push") validatePush()
  else throw new Error(`알 수 없는 명령입니다: ${command}`)
} catch (error) {
  console.error(`\n${error.message}`)
  process.exit(1)
}
