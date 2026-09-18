import { useRef, useState, type CSSProperties } from "react"
import { Link } from "react-router"
import { isApiError } from "../../../api/error"
import { GameImage } from "@/components/GameImage"
import { useToggleMyGame } from "../../../hooks/queries/gameQueries"
import { gameDetailPath } from "../../../router/paths"
import type { Game, MyGame } from "../../../types"
import GenreTagClamp from "./GenreTagClamp"
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

/** 카드 rect 기준으로 요약 팝오버 좌표를 계산한다. */
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

/** 게임 목록 카드. 캡슐 이미지 호버 시에만 요약 팝오버를 연다. */
export default function GameCard({ game, isMine }: { game: Game | MyGame; isMine: boolean }) {
  const { mutate, isPending, error } = useToggleMyGame()
  const [preview, setPreview] = useState<PopoverPosition | null>(null)
  // 팝오버 위치 계산용 카드 루트
  const cardRef = useRef<HTMLElement>(null)
  const tone = rateTone(game.positiveRate)
  const detailPath = gameDetailPath(game.id)

  /** 카드 rect 기준으로 요약 팝오버를 연다. */
  const openPreview = () => {
    const card = cardRef.current
    if (!card) return
    setPreview(popoverPositionFor(card.getBoundingClientRect()))
  }
  const closePreview = () => setPreview(null)
  const popoverStyle: PopoverStyle | undefined = preview
    ? { "--popover-x": `${preview.x}px`, "--popover-y": `${preview.y}px` }
    : undefined

  return (
    <div className="h-full">
      <article
        ref={cardRef}
        className={`relative flex h-full flex-col overflow-hidden rounded-sb-card border bg-sb-canvas-surface ${preview ? "border-sb-hairline-strong" : "border-sb-hairline-cool"}`}
      >
        {/* z-[1]: 제목 링크의 전체 클릭 영역(::after)보다 위에 두어 이미지 호버만 받는다 */}
        <div
          tabIndex={0}
          className="relative z-[1] shrink-0 rounded-sb-tag focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          onMouseEnter={openPreview}
          onMouseLeave={closePreview}
          onFocus={openPreview}
          onBlur={closePreview}
        >
          {/* 이미지 클릭도 상세로 이동(제목 스트레치 링크가 가려지므로) */}
          <Link
            to={detailPath}
            draggable={false}
            tabIndex={-1}
            className="block"
            aria-hidden="true"
          >
            <GameImage
              src={game.capsuleImageUrl}
              loading="lazy"
              className="aspect-[460/215] w-full"
            />
          </Link>
        </div>
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
              onClick={() => mutate({ gameId: game.id, isMine })}
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
          <div className="mt-auto flex flex-col gap-sb-2">
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
          <GameSummaryPopover summary={game.gameSummary} genreTags={game.tags} />
        </div>
      )}
    </div>
  )
}
