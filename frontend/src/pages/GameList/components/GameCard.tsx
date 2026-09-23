import { useEffect, useId, useOptimistic, useRef, useTransition } from "react"
import { Link } from "react-router"
import { isApiError } from "../../../api/error"
import { GameImage } from "@/components/GameImage"
import GameSummaryTooltip from "@/components/GameSummaryTooltip"
import { useCardTooltip } from "@/hooks/useCardTooltip"
import { useToggleMyGame } from "../../../hooks/queries/gameQueries"
import { gameDetailPath } from "../../../router/paths"
import type { Game, MyGame } from "../../../types"
import { PositiveRate, StarIcon } from "./GameCardParts"
import GenreTagClamp from "./GenreTagClamp"

/** 별을 연달아 눌러도 마지막 상태만 이만큼 뒤에 한 번 요청한다. */
const STAR_COMMIT_DELAY_MS = 400

/**
 * 게임 목록 카드. 카드 어디를 눌러도 상세로 이동하고(별 제외),
 * 0.25초 이상 마우스를 올리거나 포커스가 들어오면 카드 옆에 Steam 요약 툴팁이 뜬다.
 * 별은 누르는 즉시 바뀌어 보이고, 마지막 상태가 서버와 다를 때만 디바운스 후 한 번 요청한다.
 */
export default function GameCard({ game, isMine }: { game: Game | MyGame; isMine: boolean }) {
  const { mutateAsync, isPending, error } = useToggleMyGame()
  const {
    rootRef,
    isOpen: isTooltipOpen,
    tooltipClassName,
    handlers: tooltipHandlers,
  } = useCardTooltip<HTMLDivElement>()
  // 별 클릭 트랜지션이 진행 중인 동안만 화면에 보이는 값. 끝나면 서버 값(isMine)으로 돌아간다.
  const [shownMine, setShownMine] = useOptimistic(isMine)
  const [, startStarTransition] = useTransition()
  const tooltipId = useId()
  // 디바운스 대기 중인 별 클릭. 다음 클릭이 오면 false로, 시간이 지나거나 언마운트되면 true로 풀린다.
  const starWaitRef = useRef<{
    timer: ReturnType<typeof setTimeout>
    resolve: (isLast: boolean) => void
  } | null>(null)
  const detailPath = gameDetailPath(game.id)

  useEffect(
    () => () => {
      const waiting = starWaitRef.current
      if (waiting) {
        clearTimeout(waiting.timer)
        waiting.resolve(true)
      }
    },
    [],
  )

  const waitForLastStarClick = () =>
    new Promise<boolean>((resolve) => {
      const previous = starWaitRef.current
      if (previous) {
        clearTimeout(previous.timer)
        previous.resolve(false)
      }
      starWaitRef.current = {
        resolve,
        timer: setTimeout(() => {
          starWaitRef.current = null
          resolve(true)
        }, STAR_COMMIT_DELAY_MS),
      }
    })
  const toggleStar = () => {
    const next = !shownMine
    startStarTransition(async () => {
      setShownMine(next)
      const isLast = await waitForLastStarClick()
      if (!isLast || next === isMine) return
      // 실패는 useMutation의 error로 아래 알림에 표시되므로 여기서는 트랜지션만 끝낸다.
      await mutateAsync({ gameId: game.id, isMine }).catch(() => undefined)
    })
  }

  return (
    <div ref={rootRef} className="relative h-full" {...tooltipHandlers}>
      <article
        className={`relative flex h-full flex-col overflow-hidden rounded-sb-card border bg-sb-canvas-surface ${isTooltipOpen ? "border-sb-hairline-strong" : "border-sb-hairline-cool"}`}
      >
        <GameImage
          src={game.capsuleImageUrl}
          loading="lazy"
          className="aspect-[616/353] w-full shrink-0"
        />
        <div className="flex flex-1 flex-col gap-sb-3 p-sb-4">
          <div className="flex items-start justify-between gap-sb-2">
            {/* leading 여유로 truncate overflow가 g/y descender를 자르지 않게 함 */}
            <h3 className="min-w-0 flex-1 text-sb-title font-medium" title={game.title}>
              {/* after 오버레이가 카드 전체를 덮어 어디를 눌러도 상세로 이동한다. 별만 z-10으로 위에 둔다. */}
              <Link
                to={detailPath}
                draggable={false}
                aria-describedby={isTooltipOpen ? tooltipId : undefined}
                className="block truncate leading-[1.35] rounded-sb-tag after:absolute after:inset-0 after:z-[1] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                {game.title}
              </Link>
            </h3>
            <button
              type="button"
              aria-pressed={shownMine}
              aria-label={
                shownMine ? `${game.title} 내 게임 등록 해제` : `${game.title} 내 게임으로 등록`
              }
              disabled={isPending}
              onClick={toggleStar}
              className={`relative z-10 shrink-0 cursor-pointer rounded-sb-tag focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50 ${shownMine ? "text-sb-amber-text hover:text-sb-ink-mute" : "text-sb-ink-mute hover:text-sb-ink"}`}
            >
              <StarIcon filled={shownMine} />
            </button>
          </div>
          {error && (
            <p role="alert" className="text-sb-caption text-sb-neg-text">
              {isApiError(error)
                ? error.message
                : "내 게임 상태를 변경하지 못했습니다. 다시 시도해 주세요."}
            </p>
          )}
          <GenreTagClamp tags={game.tags} />
          <p className="flex gap-sb-2 text-sb-caption" title={game.gameSummary.latestPatch}>
            <span className="shrink-0 text-sb-ink-mute">최근 패치</span>
            <span className="truncate font-sb-mono">{game.gameSummary.latestPatch}</span>
          </p>
          <PositiveRate rate={game.positiveRate} />
        </div>
      </article>

      {isTooltipOpen && (
        <div className={tooltipClassName}>
          <GameSummaryTooltip id={tooltipId} summary={game.gameSummary} />
        </div>
      )}
    </div>
  )
}
