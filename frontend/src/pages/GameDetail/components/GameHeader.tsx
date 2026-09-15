import { Link } from "react-router"
import type { GameDetail } from "@/types"
import { paths } from "@/router/paths"

const UPDATED_AT_COPY = "수정일 기준 집계"

function formatCollectedAt(iso: string | null): string | null {
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ""
  return `수집 ${get("month")}-${get("day")} ${get("hour")}:${get("minute")}`
}

function formatReviewCount(count: number | null): string {
  if (count === null) return "—"
  return count.toLocaleString("en-US")
}

function formatPositiveRate(rate: number | null): string {
  if (rate === null) return "—"
  const rounded = Number.isInteger(rate) ? String(rate) : rate.toFixed(1)
  return `${rounded}%`
}

function BackArrowIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-[17px] shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M19 12H5" />
      <path d="m12 19-7-7 7-7" />
    </svg>
  )
}

interface GameHeaderProps {
  game: GameDetail
}

export function GameHeader({ game }: GameHeaderProps) {
  const collectedLabel = formatCollectedAt(game.lastCollectedAt)
  const basisText = collectedLabel ? `${collectedLabel} · ${UPDATED_AT_COPY}` : UPDATED_AT_COPY

  return (
    <header className="border-b border-sb-hairline-cool bg-sb-canvas-base">
      <div className="border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 pt-sb-3 md:px-sb-12">
        <Link
          to={paths.games}
          className="inline-flex items-center gap-1.5 text-sb-body text-sb-primary hover:text-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          <BackArrowIcon />
          게임 목록
        </Link>
      </div>

      <div className="flex flex-col gap-sb-4 border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-4 md:flex-row md:items-center md:gap-[18px] md:px-sb-12 md:pb-3.5 md:pt-sb-4">
        <div className="h-[86px] w-[184px] shrink-0 overflow-hidden rounded-sb-control border border-sb-hairline-cool bg-sb-canvas">
          {game.capsuleImageUrl ? (
            <img src={game.capsuleImageUrl} alt="" className="size-full object-cover" />
          ) : null}
        </div>

        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="flex flex-col gap-sb-2 sm:flex-row sm:items-center sm:gap-sb-2">
            <h1 className="text-sb-heading font-medium tracking-[-0.42px] text-sb-ink">
              {game.title}
            </h1>
            <p className="shrink-0 font-sb-mono text-sb-body tabular-nums text-sb-ink-mute sm:ml-auto">
              {basisText}
            </p>
          </div>

          {game.description ? (
            <p className="text-sb-body leading-normal text-sb-ink">{game.description}</p>
          ) : null}

          <div className="flex flex-wrap items-center gap-sb-2">
            {game.tags.map((tag) => (
              <span
                key={tag.id}
                className="rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 py-[3px] text-sb-body text-sb-ink-mute"
              >
                {tag.name}
              </span>
            ))}

            {game.tags.length > 0 ? (
              <span aria-hidden="true" className="hidden h-4 w-px bg-sb-hairline-strong sm:block" />
            ) : null}

            <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
              <span>출시</span>
              <span className="font-sb-mono tabular-nums text-sb-ink">
                {game.releasedOn ?? "—"}
              </span>
            </span>
            <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
              <span>리뷰</span>
              <span className="font-sb-mono tabular-nums text-sb-ink">
                {formatReviewCount(game.reviewCount)}
              </span>
            </span>
            <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
              <span>전체 긍정률</span>
              <span className="font-sb-mono tabular-nums text-sb-ink">
                {formatPositiveRate(game.positiveRate)}
              </span>
            </span>
          </div>
        </div>
      </div>
    </header>
  )
}
