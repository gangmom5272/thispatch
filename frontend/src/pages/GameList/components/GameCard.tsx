import { useRef, useState, type FocusEvent } from "react"
import { Link } from "react-router"
import { isApiError } from "../../../api/error"
import { GameImage } from "@/components/GameImage"
import { useToggleMyGame } from "../../../hooks/queries/gameQueries"
import { gameDetailPath } from "../../../router/paths"
import type { Game, MyGame } from "../../../types"
import { PositiveRate, StarIcon } from "./GameCardParts"
import GameExpandedCard from "./GameExpandedCard"
import GenreTagClamp from "./GenreTagClamp"

const EXPANDED_WIDTH = 384
const VIEWPORT_MARGIN = 16

interface ExpandAnchor {
  side: "left" | "right"
  edge: "top" | "bottom"
}

/**
 * 펼쳐진 카드가 화면 오른쪽을 넘으면 오른쪽 모서리에 맞춰 왼쪽으로,
 * 카드가 화면 아래쪽 절반에 있으면 아래 모서리에 맞춰 위로 펼친다.
 */
function expandAnchorFor(card: DOMRect): ExpandAnchor {
  const fitsRight = card.left + EXPANDED_WIDTH <= window.innerWidth - VIEWPORT_MARGIN
  const isInLowerHalf = card.top + card.height / 2 > window.innerHeight / 2
  return { side: fitsRight ? "left" : "right", edge: isInLowerHalf ? "bottom" : "top" }
}

const anchorClass: Record<ExpandAnchor["side"], string> & Record<ExpandAnchor["edge"], string> = {
  left: "left-0",
  right: "right-0",
  top: "top-0 origin-top",
  bottom: "bottom-0 origin-bottom",
}

/**
 * 게임 목록 카드. 카드에 마우스를 올리거나 포커스가 들어오면
 * 카드 자리에서 펼쳐진 카드가 이웃 카드 위로 겹쳐 뜨며 Steam 요약을 보여 준다.
 */
export default function GameCard({ game, isMine }: { game: Game | MyGame; isMine: boolean }) {
  const { mutate, isPending, error } = useToggleMyGame()
  const [anchor, setAnchor] = useState<ExpandAnchor | null>(null)
  // 펼침 방향 계산용 카드 루트
  const rootRef = useRef<HTMLDivElement>(null)
  const detailPath = gameDetailPath(game.id)
  const toggleMine = () => mutate({ gameId: game.id, isMine })

  const expand = () => {
    const root = rootRef.current
    if (!root) return
    setAnchor(expandAnchorFor(root.getBoundingClientRect()))
  }
  const collapse = () => setAnchor(null)
  const collapseIfLeaving = (event: FocusEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) collapse()
  }

  return (
    <div
      ref={rootRef}
      className="relative h-full"
      onMouseEnter={expand}
      onMouseLeave={collapse}
      onFocus={expand}
      onBlur={collapseIfLeaving}
      onKeyDown={(event) => event.key === "Escape" && collapse()}
    >
      <article
        className={`relative flex h-full flex-col overflow-hidden rounded-sb-card border bg-sb-canvas-surface ${anchor ? "border-sb-hairline-strong" : "border-sb-hairline-cool"}`}
      >
        {/* 이미지 클릭도 상세로 이동(제목 스트레치 링크가 가려지므로) */}
        <Link
          to={detailPath}
          draggable={false}
          tabIndex={-1}
          className="relative z-[1] block shrink-0"
          aria-hidden="true"
        >
          <GameImage src={game.capsuleImageUrl} loading="lazy" className="h-auto w-full" />
        </Link>
        <div className="flex flex-1 flex-col gap-sb-3 p-sb-4">
          <div className="flex items-start justify-between gap-sb-2">
            {/* leading 여유로 truncate overflow가 g/y descender를 자르지 않게 함 */}
            <h3 className="min-w-0 flex-1 text-sb-title font-medium" title={game.title}>
              <Link
                to={detailPath}
                draggable={false}
                className="block truncate leading-[1.35] rounded-sb-tag after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                {game.title}
              </Link>
            </h3>
            <button
              type="button"
              aria-pressed={isMine}
              aria-label={
                isMine ? `${game.title} 내 게임 등록 해제` : `${game.title} 내 게임으로 등록`
              }
              disabled={isPending}
              onClick={toggleMine}
              className={`relative z-10 shrink-0 cursor-pointer rounded-sb-tag focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50 ${isMine ? "text-sb-amber-text hover:text-sb-ink-mute" : "text-sb-ink-mute hover:text-sb-ink"}`}
            >
              <StarIcon filled={isMine} />
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
          <PositiveRate rate={game.positiveRate} />
        </div>
      </article>

      {anchor && (
        <div
          aria-hidden="true"
          className={`absolute z-20 hidden w-96 min-w-full transition duration-200 ease-sb-enter starting:scale-95 starting:opacity-0 motion-reduce:transition-none lg:block ${anchorClass[anchor.side]} ${anchorClass[anchor.edge]}`}
        >
          <GameExpandedCard
            game={game}
            isMine={isMine}
            detailPath={detailPath}
            isToggling={isPending}
            onToggle={toggleMine}
          />
        </div>
      )}
    </div>
  )
}
