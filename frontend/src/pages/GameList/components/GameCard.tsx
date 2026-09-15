import { useState, type CSSProperties } from "react"
import { Link } from "react-router"
import { isApiError } from "../../../api/error"
import { useToggleMyGame } from "../../../hooks/queries/gameQueries"
import { gameDetailPath } from "../../../router/paths"
import type { Game } from "../../../types"
import GameSummaryPopover from "./GameSummaryPopover"

const POSITIVE_RATE_MIN = 80
const NEUTRAL_RATE_MIN = 60

const RATE_TONE = {
  positive: {
    text: "text-sb-pos-text",
    bar: "[&::-webkit-progress-value]:bg-sb-pos [&::-moz-progress-bar]:bg-sb-pos",
  },
  neutral: {
    text: "text-sb-amber-text",
    bar: "[&::-webkit-progress-value]:bg-sb-mark [&::-moz-progress-bar]:bg-sb-mark",
  },
  negative: {
    text: "text-sb-neg-text",
    bar: "[&::-webkit-progress-value]:bg-sb-neg [&::-moz-progress-bar]:bg-sb-neg",
  },
}

const POPOVER_WIDTH = 384
const POPOVER_GAP = 8
const VIEWPORT_MARGIN = 16

interface PopoverPosition {
  x: number
  y: number
  anchor: "top" | "bottom"
}

type PopoverStyle = CSSProperties & Record<"--popover-x" | "--popover-y", string>

function popoverPositionFor(card: DOMRect): PopoverPosition {
  const fitsRight = card.right + POPOVER_GAP + POPOVER_WIDTH <= window.innerWidth - VIEWPORT_MARGIN
  const x = fitsRight
    ? card.right + POPOVER_GAP
    : Math.max(VIEWPORT_MARGIN, card.left - POPOVER_GAP - POPOVER_WIDTH)
  const isInLowerHalf = card.top + card.height / 2 > window.innerHeight / 2
  return isInLowerHalf
    ? { x, y: Math.max(VIEWPORT_MARGIN, window.innerHeight - card.bottom), anchor: "bottom" }
    : { x, y: Math.max(VIEWPORT_MARGIN, card.top), anchor: "top" }
}

function rateTone(rate: number) {
  if (rate >= POSITIVE_RATE_MIN) return RATE_TONE.positive
  if (rate >= NEUTRAL_RATE_MIN) return RATE_TONE.neutral
  return RATE_TONE.negative
}

function StarIcon({ filled }: { filled: boolean }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-5"
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth="2"
      strokeLinejoin="round"
    >
      <path d="m12 3 2.7 5.8 6.3.8-4.6 4.4 1.2 6.3L12 17.3 6.4 20.3l1.2-6.3L3 9.6l6.3-.8Z" />
    </svg>
  )
}

export default function GameCard({ game }: { game: Game }) {
  const { mutate, isPending, error } = useToggleMyGame()
  const [preview, setPreview] = useState<PopoverPosition | null>(null)
  const tone = rateTone(game.positiveRate)

  const openPreview = (target: HTMLElement) =>
    setPreview(popoverPositionFor(target.getBoundingClientRect()))
  const closePreview = () => setPreview(null)
  const popoverStyle: PopoverStyle | undefined = preview
    ? { "--popover-x": `${preview.x}px`, "--popover-y": `${preview.y}px` }
    : undefined

  return (
    <div
      onMouseEnter={(event) => openPreview(event.currentTarget)}
      onMouseLeave={closePreview}
      onFocus={(event) => openPreview(event.currentTarget)}
      onBlur={closePreview}
    >
      <article
        className={`relative overflow-hidden rounded-sb-card border bg-sb-canvas-surface ${preview ? "border-sb-hairline-strong" : "border-sb-hairline-cool"}`}
      >
        <img
          src={game.capsuleImageUrl}
          alt=""
          loading="lazy"
          className="aspect-[460/215] w-full bg-sb-canvas object-cover"
        />
        <div className="flex flex-col gap-sb-3 p-sb-4">
          <div className="flex items-start justify-between gap-sb-2">
            <h3 className="truncate text-sb-title font-medium" title={game.title}>
              <Link
                to={gameDetailPath(game.id)}
                className="rounded-sb-tag after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                {game.title}
              </Link>
            </h3>
            <button
              type="button"
              aria-pressed={game.isMine}
              aria-label={
                game.isMine ? `${game.title} 내 게임 등록 해제` : `${game.title} 내 게임으로 등록`
              }
              disabled={isPending}
              onClick={() => mutate({ gameId: game.id, isMine: game.isMine })}
              className={`relative z-10 shrink-0 cursor-pointer rounded-sb-tag focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50 ${game.isMine ? "text-sb-amber-text hover:text-sb-ink-mute" : "text-sb-ink-mute hover:text-sb-ink"}`}
            >
              <StarIcon filled={game.isMine} />
            </button>
          </div>
          {error && (
            <p role="alert" className="text-sb-caption text-sb-neg-text">
              {isApiError(error)
                ? error.message
                : "내 게임 상태를 변경하지 못했습니다. 다시 시도해 주세요."}
            </p>
          )}
          <ul className="flex flex-wrap gap-sb-1" aria-label="장르">
            {game.tags.map((tag) => (
              <li key={tag.id} className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1">
                {tag.name}
              </li>
            ))}
          </ul>
          <div className="flex flex-col gap-sb-2">
            <div className="flex items-baseline justify-between">
              <span className="text-sb-lead text-sb-ink-mute">긍정률</span>
              <span className={`font-sb-mono text-sb-title tabular-nums ${tone.text}`}>
                {game.positiveRate}%
              </span>
            </div>
            <progress
              value={game.positiveRate}
              max={100}
              aria-hidden="true"
              className={`h-1.5 w-full appearance-none overflow-hidden rounded-full border-0 bg-sb-canvas-soft [&::-webkit-progress-bar]:bg-transparent ${tone.bar}`}
            />
          </div>
        </div>
      </article>

      {preview && (
        <div
          role="presentation"
          style={popoverStyle}
          className={`pointer-events-none fixed left-(--popover-x) z-20 hidden max-h-[calc(100vh-32px)] overflow-hidden rounded-sb-card lg:block ${preview.anchor === "top" ? "top-(--popover-y)" : "bottom-(--popover-y)"}`}
        >
          <GameSummaryPopover summary={game.gameSummary} />
        </div>
      )}
    </div>
  )
}
