import { useId, type FormEvent } from "react"
import { Link, useNavigate, useParams } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { GameHeader } from "@/components/GameHeader"
import PlanSteps from "@/components/PlanSteps"
import Textarea from "@/components/Textarea"
import { useGameDetail } from "@/hooks/queries/gameQueries"
import { useCreatePlanStructure } from "@/hooks/queries/patchQueries"
import NotFound from "@/pages/NotFound"
import { gameCasesPath, paths } from "@/router/paths"
import GenreFilterDropdown from "./components/GenreFilterDropdown"
import PlanStructureResult from "./components/PlanStructureResult"
import { usePlanDraft, useUpdatePlanDraft } from "./planDraftStore"

const MAX_TEXT_LENGTH = 500

const panelClass = "rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"

export default function PlanStructurePage() {
  const { gameId } = useParams()
  if (!/^\d+$/.test(gameId ?? "")) {
    return <NotFound />
  }
  return <PlanStructureContent gameId={Number(gameId)} />
}

function PlanStructureContent({ gameId }: { gameId: number }) {
  const textId = useId()
  const { text, excludedGenreIds, structure: savedStructure, editedSlots } = usePlanDraft(gameId)
  const updateDraft = useUpdatePlanDraft()
  const navigate = useNavigate()
  const game = useGameDetail(gameId)
  const structure = useCreatePlanStructure()

  const genres = game.data?.tags ?? []
  const selectedGenreIds = genres
    .filter((genre) => !excludedGenreIds.includes(genre.id))
    .map((genre) => genre.id)
  const trimmedText = text.trim()
  const canSubmit = trimmedText.length > 0 && !structure.isPending

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canSubmit) return
    structure.mutate(
      { gameId, text: trimmedText },
      { onSuccess: (data) => updateDraft(gameId, { structure: data, editedSlots: null }) },
    )
  }

  return (
    <>
      <GameHeader gameId={gameId} back="gameDetail" />
      <main className="mx-auto flex max-w-sb-page flex-col gap-sb-4 px-sb-4 py-sb-6 md:px-sb-12">
        <PlanSteps current={0} />

        <form onSubmit={handleSubmit} className={panelClass}>
          <div className="flex flex-wrap items-baseline gap-x-sb-2 gap-y-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
            <h2 className="font-medium">다음 버전 기획안</h2>
            <p className="text-sb-ink-mute">
              자연어로 변경안을 작성하면 변경 대상의 의미부터 확인합니다
            </p>
          </div>
          <div className="flex flex-col gap-sb-3 p-sb-4">
            <label htmlFor={textId} className="sr-only">
              기획안 본문
            </label>
            <Textarea
              id={textId}
              value={text}
              maxLength={MAX_TEXT_LENGTH}
              required
              placeholder="예: Axebot의 체력을 20% 높이고 공격력을 10% 증가시킨다. 고통 4 이상 난이도에서만 적용한다."
              onChange={(event) => updateDraft(gameId, { text: event.target.value })}
            />
            <div className="flex flex-wrap items-center gap-sb-2">
              <GenreFilterDropdown
                genres={genres}
                excludedIds={excludedGenreIds}
                isLoading={game.isPending}
                onChange={(ids) => updateDraft(gameId, { excludedGenreIds: ids })}
              />
              <div className="ml-auto flex items-center gap-sb-2">
                <p className="font-sb-mono text-sb-ink-mute tabular-nums">
                  {text.length} / {MAX_TEXT_LENGTH}자
                </p>
                <Button variant="primary" type="submit" disabled={!canSubmit}>
                  {structure.isPending ? "구조화 중…" : "변경점 구조화"}
                </Button>
              </div>
            </div>
            {structure.error && (
              <div role="alert" className="flex flex-wrap items-center gap-sb-2 text-sb-neg-text">
                <p>
                  {isApiError(structure.error)
                    ? structure.error.message
                    : "기획안을 구조화하지 못했습니다. 잠시 후 다시 시도해 주세요."}
                </p>
                {isApiError(structure.error) && structure.error.status === 401 && (
                  <Link
                    to={paths.login}
                    className="rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
                  >
                    로그인하기
                  </Link>
                )}
              </div>
            )}
          </div>
        </form>

        {structure.isPending && (
          <p role="status" aria-live="polite" className={`${panelClass} p-sb-6 text-sb-ink-mute`}>
            변경점을 구조화하는 중입니다…
          </p>
        )}
        {savedStructure && !structure.isPending && (
          <PlanStructureResult
            structure={savedStructure}
            slots={editedSlots ?? savedStructure.slots}
            onSaveSlots={(slots) => updateDraft(gameId, { editedSlots: slots })}
            onSearchCases={() =>
              navigate(gameCasesPath(gameId), {
                state: {
                  planId: savedStructure.planId,
                  slots: editedSlots ?? savedStructure.slots,
                  genreIds: selectedGenreIds,
                },
              })
            }
          />
        )}
        {!savedStructure && !structure.isPending && (
          <div className={`${panelClass} flex flex-col gap-sb-2 p-sb-6`}>
            <p>아직 구조화한 결과가 없습니다.</p>
            <p className="text-sb-ink-mute">
              기획안을 입력하고 ‘변경점 구조화’를 누르면 고유명사·변경 슬롯·재진술을 여기에서
              확인합니다.
            </p>
          </div>
        )}
      </main>
    </>
  )
}
