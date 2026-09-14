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

  const prefix = firstLine.match(new RegExp(`^(${COMMIT_TYPES}):\\s*`, "i"))
  let subject = firstLine.slice(prefix?.[0].length ?? 0).trim()
  const issue = subject.match(/^\[([A-Z][A-Z0-9]*-[0-9]+)\]\s*/)
  if (issue) subject = subject.slice(issue[0].length)
  const rolePrefix = subject.match(/^\[([^\]]+)\]\s*/)
  if (rolePrefix) subject = subject.slice(rolePrefix[0].length)
  subject = subject.trim()

  if (!subject) {
    throw new Error("커밋 메시지를 입력해 주세요.")
  }

  const branch = !prefix || !issue ? getBranchParts() : undefined
  const type = prefix?.[1].toLowerCase() ?? branch.type
  const issueKey = issue?.[1] ?? branch.issueKey
  const role = rolePrefix?.[1] ?? getRole()
  const lines = message.split("\n")
  lines[0] = `${type}: [${issueKey}] [${role}] ${subject}`
  writeFileSync(messagePath, lines.join("\n"))
  console.log(`커밋 메시지 자동 수정: ${lines[0]}`)
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

const ZERO_SHA = "0".repeat(40)

/**
 * New remote branch: only commits not already on any remote.
 * Existing remote branch: only commits in the push range.
 */
function getPushLogArgs(localSha, remoteSha) {
  if (remoteSha !== ZERO_SHA) {
    return ["log", "--format=%H%x00%s", `${remoteSha}..${localSha}`]
  }
  return ["log", "--format=%H%x00%s", localSha, "--not", "--remotes"]
}

function validatePush() {
  const input = process.stdin ? readStdin() : ""
  const refs = input.split("\n").filter(Boolean)
  const commits = new Set()

  for (const ref of refs) {
    const parts = ref.trim().split(/\s+/)
    const localSha = parts[1]
    const remoteSha = parts[3]
    if (!localSha || localSha === ZERO_SHA) continue

    const messages = runGit(getPushLogArgs(localSha, remoteSha || ZERO_SHA))
      .split("\n")
      .filter(Boolean)

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
  else throw new Error(`알 수 없는 명령입니다: ${command}`)
} catch (error) {
  console.error(`\n${error.message}`)
  process.exit(1)
}
