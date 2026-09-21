import { useEffect, useId, useRef, useState, type FocusEvent } from "react"
import { Link } from "react-router"
import { isApiError } from "../../../api/error"
import { GameImage } from "@/components/GameImage"
import { useToggleMyGame } from "../../../hooks/queries/gameQueries"
import { gameDetailPath } from "../../../router/paths"
import type { Game, MyGame } from "../../../types"
import { PositiveRate, StarIcon } from "./GameCardParts"
import GameSummaryTooltip from "./GameSummaryTooltip"
import GenreTagClamp from "./GenreTagClamp"

const TOOLTIP_WIDTH = 384
/** 카드와 툴팁 사이 간격(ml/mr-sb-2) */
const TOOLTIP_GAP = 8
const VIEWPORT_MARGIN = 16
/** 툴팁은 열리기 전엔 높이를 알 수 없어 아래로 넘칠지 판단할 때 쓰는 추정 높이 */
const TOOLTIP_ESTIMATED_HEIGHT = 360
/** 카드를 스치기만 할 때 툴팁이 깜빡이지 않도록 이만큼 머물러야 연다. */
const OPEN_DELAY_MS = 250
/** 별을 연달아 눌러도 마지막 상태만 이만큼 뒤에 한 번 요청한다. */
const STAR_COMMIT_DELAY_MS = 400

interface TooltipAnchor {
  side: "left" | "right"
  edge: "top" | "bottom"
}

/**
 * Steam 스토어처럼 카드 오른쪽 위 모서리에 맞춰 붙이되, 화면 오른쪽을 넘으면 왼쪽에 붙이고
 * 화면 아래를 넘을 것 같으면 아래 모서리에 맞춰 위로 자란다.
 */
function tooltipAnchorFor(card: DOMRect): TooltipAnchor {
  const fitsRight = card.right + TOOLTIP_GAP + TOOLTIP_WIDTH <= window.innerWidth - VIEWPORT_MARGIN
  const fitsBelow = card.top + TOOLTIP_ESTIMATED_HEIGHT <= window.innerHeight - VIEWPORT_MARGIN
  return { side: fitsRight ? "right" : "left", edge: fitsBelow ? "top" : "bottom" }
}

const anchorClass: Record<TooltipAnchor["side"], string> & Record<TooltipAnchor["edge"], string> = {
  right: "left-full ml-sb-2 starting:-translate-x-1",
  left: "right-full mr-sb-2 starting:translate-x-1",
  top: "top-0",
  bottom: "bottom-0",
}

/**
 * 게임 목록 카드. 카드 어디를 눌러도 상세로 이동하고(별 제외),
 * 0.25초 이상 마우스를 올리거나 포커스가 들어오면 카드 옆에 Steam 요약 툴팁이 뜬다.
 * 별은 누르는 즉시 바뀌어 보이고, 마지막 상태가 서버와 다를 때만 디바운스 후 한 번 요청한다.
 */
export default function GameCard({ game, isMine }: { game: Game | MyGame; isMine: boolean }) {
  const { mutate, isPending, error } = useToggleMyGame()
  const [anchor, setAnchor] = useState<TooltipAnchor | null>(null)
  // 서버 응답 전까지 화면에만 반영하는 별 상태. null이면 서버 값(isMine)을 그대로 쓴다.
  const [optimisticMine, setOptimisticMine] = useState<boolean | null>(null)
  const shownMine = optimisticMine ?? isMine
  const tooltipId = useId()
  // 툴팁 방향 계산용 카드 루트
  const rootRef = useRef<HTMLDivElement>(null)
  // 호버 지연 타이머 — 카드를 벗어나거나 언마운트되면 취소
  const openTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  // 별 디바운스 타이머와, 언마운트 시 바로 실행할 대기 중 요청
  const starTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const pendingStarCommitRef = useRef<(() => void) | null>(null)
  const detailPath = gameDetailPath(game.id)

  useEffect(
    () => () => {
      clearTimeout(openTimerRef.current)
      clearTimeout(starTimerRef.current)
      pendingStarCommitRef.current?.()
    },
    [],
  )

  const commitStar = (next: boolean) => {
    pendingStarCommitRef.current = null
    if (next === isMine) {
      setOptimisticMine(null)
      return
    }
    mutate({ gameId: game.id, isMine }, { onSettled: () => setOptimisticMine(null) })
  }
  const toggleStar = () => {
    const next = !shownMine
    setOptimisticMine(next)
    clearTimeout(starTimerRef.current)
    pendingStarCommitRef.current = () => commitStar(next)
    starTimerRef.current = setTimeout(pendingStarCommitRef.current, STAR_COMMIT_DELAY_MS)
  }

  const open = () => {
    const root = rootRef.current
    if (!root) return
    setAnchor(tooltipAnchorFor(root.getBoundingClientRect()))
  }
  const close = () => {
    clearTimeout(openTimerRef.current)
    setAnchor(null)
  }
  const openAfterDelay = () => {
    clearTimeout(openTimerRef.current)
    openTimerRef.current = setTimeout(open, OPEN_DELAY_MS)
  }
  const closeIfLeaving = (event: FocusEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) close()
  }

  return (
    <div
      ref={rootRef}
      className="relative h-full"
      onMouseEnter={openAfterDelay}
      onMouseLeave={close}
      onFocus={open}
      onBlur={closeIfLeaving}
      onKeyDown={(event) => event.key === "Escape" && close()}
    >
      <article
        className={`relative flex h-full flex-col overflow-hidden rounded-sb-card border bg-sb-canvas-surface ${anchor ? "border-sb-hairline-strong" : "border-sb-hairline-cool"}`}
      >
        <GameImage src={game.capsuleImageUrl} loading="lazy" className="h-auto w-full shrink-0" />
        <div className="flex flex-1 flex-col gap-sb-3 p-sb-4">
          <div className="flex items-start justify-between gap-sb-2">
            {/* leading 여유로 truncate overflow가 g/y descender를 자르지 않게 함 */}
            <h3 className="min-w-0 flex-1 text-sb-title font-medium" title={game.title}>
              {/* after 오버레이가 카드 전체를 덮어 어디를 눌러도 상세로 이동한다. 별만 z-10으로 위에 둔다. */}
              <Link
                to={detailPath}
                draggable={false}
                aria-describedby={anchor ? tooltipId : undefined}
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

      {anchor && (
        <div
          className={`pointer-events-none absolute z-20 hidden w-96 transition duration-200 ease-sb-enter starting:opacity-0 motion-reduce:transition-none lg:block ${anchorClass[anchor.side]} ${anchorClass[anchor.edge]}`}
        >
          <GameSummaryTooltip id={tooltipId} game={game} />
        </div>
      )}
    </div>
  )
}
