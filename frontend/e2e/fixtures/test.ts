import { test as base, expect, type Request, type Route } from "@playwright/test"
import {
  account,
  games,
  genres,
  list,
  meta,
  plan,
  savedPlan,
  similarCase,
  summary,
  trends,
} from "./data"

type Reply = { data?: unknown; status?: number; message?: string }
type Handler = (request: Request) => Reply | Promise<Reply>

export async function respond(
  route: Route,
  { data = null, status = 200, message = "성공했습니다." }: Reply,
) {
  await route.fulfill({
    status,
    json: {
      success: status < 400,
      code: String(status),
      message,
      responsedAt: "2026-09-22 10:00:00",
      data,
    },
  })
}

// Each test owns its route handlers and mutable account data. No production mock store is shared.
export const test = base.extend<{ api: Map<string, Handler> }>({
  storageState: {
    cookies: [],
    origins: [
      {
        origin: "http://localhost:5174",
        localStorage: [{ name: "thispatch.accessToken", value: "e2e-token" }],
      },
    ],
  },
  api: [
    async ({ page }, use) => {
      let user = { ...account }
      let withdrawn = false
      const mine = new Set<number>()
      const handlers = new Map<string, Handler>()
      handlers.set("GET /session", (request) => ({
        data: {
          authenticated: !withdrawn && Boolean(request.headers().authorization),
          user:
            !withdrawn && request.headers().authorization
              ? { id: 1, nickname: user.nickname }
              : null,
        },
      }))
      handlers.set("POST /auth/login", (request) => {
        const body = request.postDataJSON()
        return !withdrawn && body.email === user.email && body.password === user.password
          ? { data: { accessToken: "e2e-token", refreshToken: "e2e-refresh" } }
          : { status: 401, message: "이메일 또는 비밀번호가 일치하지 않습니다." }
      })
      handlers.set("POST /auth/signup", (request) => {
        user = request.postDataJSON()
        return {}
      })
      handlers.set("PATCH /members/me/nickname", (request) => {
        user.nickname = request.postDataJSON().nickname
        return { data: { nickname: user.nickname } }
      })
      handlers.set("PATCH /members/me/password", (request) => {
        const body = request.postDataJSON()
        if (body.currentPassword !== user.password) {
          return { status: 400, message: "현재 비밀번호가 일치하지 않습니다." }
        }
        user.password = body.newPassword
        return {}
      })
      handlers.set("DELETE /members/me", () => {
        withdrawn = true
        return {}
      })
      handlers.set("GET /genres", () => ({ data: { items: genres } }))
      handlers.set("GET /games", (request) => {
        const params = new URL(request.url()).searchParams
        let items = games.filter((game) => game.title.includes(params.get("search") ?? ""))
        if (params.get("developer")) {
          items = items.filter((game) => game.gameSummary.developer === params.get("developer"))
        }
        if (params.get("sort") === "POSITIVE_RATE_ASC") {
          items = [...items].sort((a, b) => a.positiveRate - b.positiveRate)
        }
        return { data: list(items.map((game) => ({ ...game, isMine: mine.has(game.id) }))) }
      })
      handlers.set("GET /members/me/games", () => ({
        data: list(games.filter((game) => mine.has(game.id))),
      }))
      for (const game of games) {
        handlers.set(`GET /games/${game.id}`, () => ({
          data: { ...game, ...game.gameSummary, isMine: mine.has(game.id), lastCollectedAt: null },
        }))
        handlers.set(`POST /games/${game.id}/my-game`, () => {
          mine.add(game.id)
          return {}
        })
        handlers.set(`DELETE /games/${game.id}/my-game`, () => {
          mine.delete(game.id)
          return {}
        })
        handlers.set(`GET /games/${game.id}/reaction-trends`, () => ({ data: trends }))
        handlers.set(`GET /games/${game.id}/summaries/reaction-trends`, () => ({
          data: { meta, summary },
        }))
        handlers.set(`POST /games/${game.id}/plan-structures`, (request) => ({
          data: { ...plan, gameId: game.id, rawText: request.postDataJSON().text },
        }))
        handlers.set(`POST /games/${game.id}/case-searches`, (request) => ({
          data: {
            ...request.postDataJSON(),
            gameId: game.id,
            status: "COMPLETED",
            totalCount: 1,
            notices: [],
            groups: [
              {
                outcome: "POSITIVE_SHIFT",
                name: "긍정 급변",
                caseCount: 1,
                observedPatterns: [],
                cases: [similarCase],
              },
            ],
          },
        }))
      }
      handlers.set("GET /games/8/patches/patch-8", () => ({
        data: {
          gameId: 8,
          patchId: "patch-8",
          title: similarCase.patchTitle,
          patchedOn: similarCase.patchedOn,
          publishedAt: "2026-09-15T10:00:00Z",
          body: "Enemy health increased by 20%.",
          bodyFormat: "TEXT",
          url: "https://example.com/patch",
        },
      }))
      handlers.set("GET /patches/patch-8/translation", () => ({
        data: {
          translatedTitle: "전투 밸런스 패치 번역",
          translatedBody: "적의 체력이 20% 증가했습니다.",
        },
      }))
      handlers.set("GET /members/me/patch-plans", () => ({
        data: list([
          { ...savedPlan, rawTextPreview: savedPlan.rawText, slotCount: 1, unknownEntityCount: 0 },
        ]),
      }))
      handlers.set("GET /members/me/patch-plans/101", () => ({ data: savedPlan }))
      const unexpected: string[] = []
      await page.route(
        (url) => url.pathname.startsWith("/api/"),
        async (route) => {
          const request = route.request()
          const key = `${request.method()} ${new URL(request.url()).pathname.slice(4)}`
          const handler = handlers.get(key)
          if (!handler) {
            unexpected.push(key)
          }
          await respond(
            route,
            handler ? await handler(request) : { status: 404, message: `테스트 응답 없음: ${key}` },
          )
        },
      )
      await use(handlers)
      expect(unexpected, "모든 API 요청에는 명시적인 테스트 응답이 있어야 합니다.").toEqual([])
    },
    { auto: true },
  ],
})
export { expect }
