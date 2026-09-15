import { delay, http, HttpResponse } from "msw"
import type { PlanStructure } from "../../types"
import { mockGames } from "../games"
import { userFromAuthHeader } from "../lib/authStore"
import { errorBody, okEnvelope } from "../lib/envelope"

const baseURL = (import.meta.env.VITE_API_BASE_URL ?? "/api").replace(/\/$/, "")

const sample: Omit<PlanStructure, "gameId" | "rawText" | "genreIds"> = {
  entities: [
    { id: 1, name: "Axebot", role: "ENEMY", source: "RULE", editable: false },
    { id: 2, name: "고통 4", role: "DIFFICULTY", source: "GAME_LEXICON", editable: false },
    { id: 3, name: "Wraith", role: "UNKNOWN", source: "UNKNOWN", editable: false },
  ],
  slots: [
    {
      id: 1,
      targetName: "Axebot",
      targetRole: "ENEMY",
      attribute: "HP",
      direction: "INCREASE",
      magnitude: "+20%",
      scope: "고통 4 이상",
      editable: true,
    },
    {
      id: 2,
      targetName: "Axebot",
      targetRole: "ENEMY",
      attribute: "ATK",
      direction: "INCREASE",
      magnitude: "+10%",
      scope: "고통 4 이상",
      editable: true,
    },
    {
      id: 3,
      targetName: "Wraith",
      targetRole: "UNKNOWN",
      attribute: "출현 빈도",
      direction: "MODIFY",
      magnitude: null,
      scope: null,
      editable: true,
    },
  ],
  restatement: {
    text: "적(Axebot)의 체력·공격력을 상향하고, 적용 범위는 고통 4 이상으로 제한하는 변경으로 이해했습니다.",
    highlights: {
      primaryRole: "ENEMY",
      attributes: ["HP", "ATK"],
      direction: "INCREASE",
      scope: "고통 4 이상",
    },
    warnings: [
      {
        code: "UNKNOWN_ENTITY",
        message: "Wraith는 UNKNOWN이라 검색 가중치가 낮습니다.",
        entityName: "Wraith",
      },
    ],
  },
}

export const patchHandlers = [
  http.post(`${baseURL}/games/:gameId/plan-structures`, async ({ request, params }) => {
    await delay(600)
    if (!userFromAuthHeader(request)) {
      return HttpResponse.json(errorBody("401", "인증이 필요합니다."), { status: 401 })
    }
    const game = mockGames.find((item) => item.id === Number(params.gameId))
    if (!game) {
      return HttpResponse.json(errorBody("404", "게임을 찾을 수 없습니다."), { status: 404 })
    }
    const body = (await request.json()) as { text?: string }
    const text = body.text?.trim()
    if (!text) {
      return HttpResponse.json(errorBody("400", "기획안 본문이 비어 있습니다."), { status: 400 })
    }
    const data: PlanStructure = {
      gameId: game.id,
      rawText: text,
      genreIds: game.tags.map((tag) => tag.id),
      ...sample,
    }
    return HttpResponse.json(okEnvelope(data))
  }),
]
