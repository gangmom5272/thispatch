import { useId, useState } from "react"
import { Link } from "react-router"
import RateBar from "@/components/RateBar"
import { GAME_GENRES } from "@/constants/games"
import { formatDeltaPp, formatPercent } from "@/lib/format"
import { gameCaseDetailPath } from "@/router/paths"
import type { CaseDetailLocationState, CaseOutcome, SimilarCase } from "@/types"
import CaseGamePopover from "./CaseGamePopover"

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

interface GameInfoAreaProps {
  item: SimilarCase
  detailPath: string
  detailState: CaseDetailLocationState
}

function GameInfoArea({ item, detailPath, detailState }: GameInfoAreaProps) {
  const popoverId = useId()
  const [open, setOpen] = useState(false)
  const show = () => setOpen(true)
  const hide = () => setOpen(false)

  return (
    <div className="relative" onMouseLeave={hide}>
      <Link
        to={detailPath}
        state={detailState}
        aria-label={`${item.gameTitle} 사례 상세 비교`}
        aria-describedby={open ? popoverId : undefined}
        onMouseEnter={show}
        onFocus={show}
        onBlur={hide}
        onKeyDown={(event) => event.key === "Escape" && hide()}
        className="relative z-10 block rounded-t-sb-control focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
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
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-sb-2 px-sb-4 pt-sb-3">
        <h3 className="min-w-0 text-sb-title font-medium">{item.gameTitle}</h3>
        <span className="flex items-center gap-sb-1 rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2 py-px">
          <span className="text-sb-ink-mute">유사도</span>
          <span className="font-sb-mono font-medium text-sb-primary tabular-nums">
            {item.similarity.toFixed(1)}
          </span>
        </span>
        {open && (
          <CaseGamePopover
            id={popoverId}
            gameId={item.gameId}
            title={item.gameTitle}
            capsuleImageUrl={item.capsuleImageUrl}
          />
        )}
      </div>
    </div>
  )
}

interface CaseCardProps {
  item: SimilarCase
  gameId: number
  outcome: CaseOutcome
  outcomeName: string
}

export default function CaseCard({ item, gameId, outcome, outcomeName }: CaseCardProps) {
  const genreNames = item.genres
    .map((id) => GAME_GENRES.find((genre) => genre.id === id)?.name)
    .filter((name) => name !== undefined)
  const tone = deltaTone(item.deltaPp)
  const detailPath = gameCaseDetailPath(gameId, item.patchId)
  const detailState: CaseDetailLocationState = { case: item, outcome, outcomeName }

  return (
    <li className="relative flex flex-col rounded-sb-control border border-sb-hairline-cool bg-sb-canvas-surface hover:border-sb-hairline-strong has-focus-visible:border-sb-primary">
      <GameInfoArea item={item} detailPath={detailPath} detailState={detailState} />
      <div className="flex flex-col gap-sb-2 px-sb-4 pt-sb-2 pb-sb-3">
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
          <span className={`ml-auto font-medium ${tone}`}>{formatDeltaPp(item.deltaPp)}</span>
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
          to={detailPath}
          state={detailState}
          className="mt-sb-1 self-end rounded-sb-tag text-sb-primary after:absolute after:inset-0 after:rounded-sb-control focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          사례 상세 비교 <span aria-hidden="true">→</span>
        </Link>
      </div>
    </li>
  )
}
