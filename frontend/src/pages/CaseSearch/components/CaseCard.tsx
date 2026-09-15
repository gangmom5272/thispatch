import { useId, useState } from "react"
import { Link } from "react-router"
import { GAME_GENRES } from "@/constants/games"
import { gameCaseDetailPath } from "@/router/paths"
import type { SimilarCase } from "@/types"
import CaseGamePopover from "./CaseGamePopover"

function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

function formatDelta(value: number): string {
  const sign = value > 0 ? "+" : value < 0 ? "-" : ""
  return `${sign}${Math.abs(value).toFixed(1)}%p`
}

function formatFollowUp(ratio: number | null): string {
  return ratio === null
    ? "후속 패치까지 · 정보 없음"
    : `후속 패치까지 · 평소 주기의 ${ratio.toFixed(1)}배`
}

function deltaTone(delta: number): string {
  if (delta > 0) return "text-sb-pos-text"
  if (delta < 0) return "text-sb-neg-text"
  return "text-sb-ink-mute"
}

function RateBar({ before, after }: { before: number; after: number }) {
  const kept = Math.max(0, Math.min(before, after))
  const changed = Math.abs(after - before)
  return (
    <svg
      role="img"
      aria-label={`긍정률 ${formatPercent(before)}에서 ${formatPercent(after)}로 변화`}
      className="h-[10px] w-full"
      preserveAspectRatio="none"
    >
      <rect width="100%" height="100%" rx="4" className="fill-sb-canvas-soft" />
      <rect width={`${kept}%`} height="100%" className="fill-sb-hairline-strong" />
      <rect
        x={`${kept}%`}
        width={`${changed}%`}
        height="100%"
        className={after >= before ? "fill-sb-pos" : "fill-sb-neg"}
      />
    </svg>
  )
}

function SummaryRow({ label, tone, text }: { label: string; tone: string; text: string }) {
  return (
    <div className="flex items-start gap-sb-2">
      <span className={`shrink-0 rounded-sb-tag border px-sb-2 py-px font-medium ${tone}`}>
        {label}
      </span>
      <p className="leading-relaxed">{text}</p>
    </div>
  )
}

function GameTitle({ item }: { item: SimilarCase }) {
  const popoverId = useId()
  const [open, setOpen] = useState(false)
  const show = () => setOpen(true)
  const hide = () => setOpen(false)

  return (
    <div className="relative z-10 min-w-0" onMouseEnter={show} onMouseLeave={hide}>
      <h3 className="text-sb-title font-medium">
        <button
          type="button"
          aria-describedby={open ? popoverId : undefined}
          aria-expanded={open}
          onFocus={show}
          onBlur={hide}
          onClick={() => setOpen((prev) => !prev)}
          onKeyDown={(event) => event.key === "Escape" && hide()}
          className="cursor-help rounded-sb-tag text-left underline decoration-sb-hairline-strong decoration-dotted underline-offset-4 hover:decoration-sb-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          {item.gameTitle}
        </button>
      </h3>
      {open && (
        <CaseGamePopover
          id={popoverId}
          gameId={item.gameId}
          title={item.gameTitle}
          capsuleImageUrl={item.capsuleImageUrl}
        />
      )}
    </div>
  )
}

export default function CaseCard({ item, gameId }: { item: SimilarCase; gameId: number }) {
  const genreNames = item.genres
    .map((id) => GAME_GENRES.find((genre) => genre.id === id)?.name)
    .filter((name) => name !== undefined)
  const tone = deltaTone(item.deltaPp)

  return (
    <li className="relative flex flex-col rounded-sb-control border border-sb-hairline-cool bg-sb-canvas-surface hover:border-sb-hairline-strong has-focus-visible:border-sb-primary">
      {item.capsuleImageUrl ? (
        <img
          src={item.capsuleImageUrl}
          alt=""
          loading="lazy"
          className="aspect-[460/215] w-full rounded-t-sb-control bg-sb-canvas object-cover"
        />
      ) : (
        <div
          aria-hidden="true"
          className="aspect-[460/215] w-full rounded-t-sb-control bg-sb-canvas"
        />
      )}
      <div className="flex flex-col gap-sb-2 px-sb-4 py-sb-3">
        <div className="flex flex-wrap items-center justify-between gap-sb-2">
          <GameTitle item={item} />
          <span className="flex items-center gap-sb-1 rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2 py-px">
            <span className="text-sb-ink-mute">유사도</span>
            <span className="font-sb-mono font-medium text-sb-primary tabular-nums">
              {item.similarity.toFixed(1)}
            </span>
          </span>
        </div>
        {genreNames.length > 0 && <p className="text-sb-ink-mute">{genreNames.join(" · ")}</p>}
        <p className="font-sb-mono text-sb-ink-mute tabular-nums">
          {item.patchTitle} · {item.patchedOn} · 리뷰 {item.reviewCount.toLocaleString("en-US")}건
        </p>

        <hr className="border-sb-hairline" />

        <div className="flex items-center gap-sb-2 font-sb-mono tabular-nums">
          <span className="font-sb-sans text-sb-ink-mute">긍정률</span>
          <span className="text-sb-ink-mute">{formatPercent(item.positiveRateBefore)}</span>
          <span aria-hidden="true" className="text-sb-ink-mute">
            →
          </span>
          <span className={`font-medium ${tone}`}>{formatPercent(item.positiveRateAfter)}</span>
          <span className={`ml-auto font-medium ${tone}`}>{formatDelta(item.deltaPp)}</span>
        </div>
        <RateBar before={item.positiveRateBefore} after={item.positiveRateAfter} />

        <hr className="border-sb-hairline" />

        <div className="flex flex-col gap-sb-1 text-sb-ink-mute">
          <p>
            평소 패치 주기{" "}
            <span className="font-sb-mono tabular-nums">
              {item.avgPatchIntervalDays === null
                ? "정보 없음"
                : `${item.avgPatchIntervalDays.toFixed(1)}일`}
            </span>
          </p>
          <p>{formatFollowUp(item.followUpSpeedRatio)}</p>
        </div>

        <hr className="border-sb-hairline" />

        <div className="flex flex-col gap-sb-2">
          <SummaryRow
            label="공통"
            tone="border-sb-line-green bg-sb-tint-green text-sb-pos-text"
            text={item.commonalitySummary}
          />
          <SummaryRow
            label="차이"
            tone="border-sb-line-amber bg-sb-tint-amber text-sb-amber-text"
            text={item.differenceSummary}
          />
        </div>

        <Link
          to={gameCaseDetailPath(gameId, item.patchId)}
          state={{ case: item }}
          className="mt-sb-1 self-end rounded-sb-tag text-sb-primary after:absolute after:inset-0 after:rounded-sb-control focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          사례 상세 비교 <span aria-hidden="true">→</span>
        </Link>
      </div>
    </li>
  )
}
