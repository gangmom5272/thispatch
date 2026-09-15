import { Link, useLocation, useNavigate, useParams } from "react-router"
import { GameHeader } from "@/components/GameHeader"
import PlanSteps from "@/components/PlanSteps"
import NotFound from "@/pages/NotFound"
import { GAME_DETAIL_TABS, gameDetailTabPath } from "@/router/paths"
import type { CaseDetailLocationState, CaseOutcome } from "@/types"
import ComparisonPanel from "./components/ComparisonPanel"
import PatchNotePanel from "./components/PatchNotePanel"
import ReactionPanel from "./components/ReactionPanel"

const panelClass = "rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
const backClass =
  "inline-flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline bg-sb-canvas-base px-sb-3 text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
const linkClass =
  "rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

const OUTCOMES: CaseOutcome[] = ["NEGATIVE_SHIFT", "NO_CHANGE", "POSITIVE_SHIFT"]

const outcomeTone: Record<CaseOutcome, string> = {
  NEGATIVE_SHIFT: "bg-sb-tint-red text-sb-neg-text",
  NO_CHANGE: "bg-sb-canvas-soft text-sb-ink-mute",
  POSITIVE_SHIFT: "bg-sb-tint-green text-sb-pos-text",
}

function isDetailState(value: unknown): value is CaseDetailLocationState {
  if (!value || typeof value !== "object") {
    return false
  }
  const state = value as Partial<CaseDetailLocationState>
  return (
    typeof state.case === "object" &&
    state.case !== null &&
    typeof state.outcomeName === "string" &&
    OUTCOMES.includes(state.outcome as CaseOutcome)
  )
}

export default function CaseDetailPage() {
  const { gameId, patchId } = useParams()
  if (!/^\d+$/.test(gameId ?? "") || !patchId) {
    return <NotFound />
  }
  return <CaseDetailContent gameId={Number(gameId)} patchId={patchId} />
}

function CaseDetailContent({ gameId, patchId }: { gameId: number; patchId: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const state = isDetailState(location.state) ? location.state : null
  const planPath = gameDetailTabPath(gameId, GAME_DETAIL_TABS.plan)

  return (
    <>
      <GameHeader gameId={gameId} back="gameDetail" />
      <div className="border-b border-sb-hairline bg-sb-canvas-base">
        <div className="mx-auto flex max-w-sb-page flex-wrap items-center gap-sb-3 px-sb-4 py-sb-2 md:px-sb-12">
          {state ? (
            <button type="button" onClick={() => navigate(-1)} className={backClass}>
              <span aria-hidden="true">←</span>
              사례 목록
            </button>
          ) : (
            <Link to={planPath} className={backClass}>
              <span aria-hidden="true">←</span>
              기획안 입력
            </Link>
          )}
          <div className="flex min-w-0 flex-wrap items-center gap-sb-2">
            <h1 className="text-sb-lead font-medium">
              {state ? state.case.gameTitle : "사례 상세 비교"}
            </h1>
            {state && (
              <>
                <p className="font-sb-mono text-sb-ink-mute tabular-nums">
                  {state.case.patchTitle} · {state.case.patchedOn}
                </p>
                <span
                  className={`rounded-sb-tag px-sb-2 py-px font-medium ${outcomeTone[state.outcome]}`}
                >
                  {state.outcomeName}
                </span>
              </>
            )}
          </div>
          {state && (
            <p className="flex items-center gap-sb-2 rounded-sb-control border border-sb-hairline-cool bg-sb-canvas px-sb-3 py-sb-1 sm:ml-auto">
              <span className="text-sb-ink-mute">유사도</span>
              <span className="font-sb-mono font-medium text-sb-primary tabular-nums">
                {state.case.similarity.toFixed(1)}
              </span>
            </p>
          )}
        </div>
      </div>

      <main className="mx-auto flex max-w-sb-page flex-col gap-sb-4 px-sb-4 py-sb-6 md:px-sb-12">
        <PlanSteps current={2} />

        {!state ? (
          <div className={`${panelClass} flex flex-col gap-sb-2 p-sb-6`}>
            <p>비교할 사례 정보가 없습니다.</p>
            <p className="text-sb-ink-mute">
              사례 상세 비교는 유사 사례 검색 결과의 카드에서 열 수 있습니다. 기획안을 구조화하고
              유사 사례를 검색한 뒤 다시 시도하세요.
            </p>
            <Link to={planPath} className={`self-start ${linkClass}`}>
              기획안 입력으로 이동
            </Link>
          </div>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-sb-4 lg:grid-cols-[46fr_54fr]">
              <PatchNotePanel
                gameId={state.case.gameId}
                patchId={patchId}
                gameTitle={state.case.gameTitle}
                patchTitle={state.case.patchTitle}
              />
              <ReactionPanel item={state.case} />
            </div>
            <ComparisonPanel comparison={state.case.comparison} />
          </>
        )}
      </main>
    </>
  )
}
