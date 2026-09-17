import { useState } from "react"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { useLanguageAnalysisDetail } from "@/hooks/queries/languageAnalysisQueries"
import { cn } from "@/lib/cn"
import { formatPercent } from "@/lib/format"
import type { LanguageAnalysisDetail, LanguageShare } from "@/types"
import ReviewCard from "./ReviewCard"

const REPRESENTATIVE_REVIEW_COUNT = 4
const RATE_GRIDLINES = [25, 50, 75]

export const LANGUAGE_ROW_GRID =
  "grid grid-cols-[26px_minmax(0,1fr)] items-center gap-x-sb-4 gap-y-sb-2 md:grid-cols-[26px_180px_280px_1fr]"

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className={cn(
        "mx-auto size-[15px] transition-transform motion-reduce:transition-none",
        open ? "rotate-90 text-sb-primary" : "text-sb-ink-mute",
      )}
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="m9 18 6-6-6-6" />
    </svg>
  )
}

function Bar({
  value,
  fillClass,
  className,
  gridlines = false,
}: {
  value: number
  fillClass: string
  className: string
  gridlines?: boolean
}) {
  return (
    <svg aria-hidden="true" className={className} preserveAspectRatio="none">
      <rect width="100%" height="100%" rx="3" className="fill-sb-canvas-soft" />
      <rect width={`${Math.max(0, Math.min(100, value))}%`} height="100%" className={fillClass} />
      {gridlines
        ? RATE_GRIDLINES.map((p) => (
            <rect key={p} x={`${p}%`} width="1" height="100%" className="fill-sb-canvas-base/70" />
          ))
        : null}
    </svg>
  )
}

function summarySource(detail: LanguageAnalysisDetail): string {
  const { summary, languageCode } = detail
  const source = summary.selection
    ? `${summary.selection.description} 기준`
    : `${summary.targetReviewCount.toLocaleString("en-US")}건`
  return `최근 ${summary.targetPeriod.dayCount}일 ${languageCode} 리뷰 · ${source}`
}

function LanguageDetail({ gameId, languageCode }: { gameId: number; languageCode: string }) {
  const query = useLanguageAnalysisDetail(gameId, languageCode)

  if (query.isPending) {
    return (
      <div
        aria-busy="true"
        className="flex flex-col gap-[14px] pb-sb-5 pl-sb-4 pr-sb-4 pt-sb-4 md:pl-[42px] md:pr-[18px]"
      >
        <div className="h-[112px] animate-pulse rounded-sb-control bg-sb-canvas-surface" />
        <div className="grid gap-[14px] md:grid-cols-2">
          <div className="h-[147px] animate-pulse rounded-sb-control bg-sb-canvas-surface" />
          <div className="h-[147px] animate-pulse rounded-sb-control bg-sb-canvas-surface" />
        </div>
      </div>
    )
  }

  if (query.isError) {
    return (
      <div
        role="alert"
        className="flex flex-wrap items-center gap-sb-3 pb-sb-5 pl-sb-4 pr-sb-4 pt-sb-4 md:pl-[42px] md:pr-[18px]"
      >
        <p className="text-sb-ink">
          {isApiError(query.error)
            ? query.error.message
            : "대표 리뷰를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
        </p>
        <Button variant="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
          다시 시도
        </Button>
      </div>
    )
  }

  const { summary, representativeReviews } = query.data
  const reviews = representativeReviews.slice(0, REPRESENTATIVE_REVIEW_COUNT)
  let summaryText = summary.text ?? "요약 본문이 비어 있습니다."
  if (summary.status === "UNAVAILABLE") {
    summaryText = summary.message ?? "AI 요약을 일시적으로 이용할 수 없습니다."
  } else if (summary.status === "SKIPPED") {
    summaryText = "표본이 부족해 AI 요약을 건너뛰었습니다."
  }

  return (
    <div className="flex flex-col gap-[14px] pb-sb-5 pl-sb-4 pr-sb-4 pt-sb-4 md:pl-[42px] md:pr-[18px]">
      <section
        aria-label="AI 대표 리뷰 요약"
        className="flex flex-col gap-sb-2 rounded-sb-control bg-sb-canvas-surface px-[15px] py-[13px]"
      >
        <div className="flex flex-wrap items-center gap-[10px]">
          <span className="rounded-sb-tag bg-sb-tint-blue px-[7px] py-0.5 font-sb-mono font-medium text-sb-primary">
            AI
          </span>
          <h3 className="font-medium text-sb-ink">대표 리뷰 요약</h3>
          <p className="font-sb-mono text-sb-ink-mute tabular-nums md:ml-auto">
            {summarySource(query.data)}
          </p>
        </div>
        <p className="text-sb-ink">{summaryText}</p>
      </section>

      {reviews.length === 0 ? (
        <p className="text-sb-ink-mute">이 구간에 표시할 대표 리뷰가 없습니다.</p>
      ) : (
        <div className="grid gap-[14px] md:grid-cols-2">
          {reviews.map((review) => (
            <ReviewCard key={review.id} review={review} />
          ))}
        </div>
      )}
    </div>
  )
}

interface LanguageRowProps {
  gameId: number
  language: LanguageShare
}

export default function LanguageRow({ gameId, language }: LanguageRowProps) {
  const [isOpen, setIsOpen] = useState(false)
  const panelId = `language-panel-${language.languageCode}`

  return (
    <li className={cn(isOpen && "bg-sb-canvas-night-soft")}>
      <button
        type="button"
        aria-expanded={isOpen}
        aria-controls={panelId}
        onClick={() => setIsOpen((prev) => !prev)}
        className={cn(
          LANGUAGE_ROW_GRID,
          "min-h-[52px] w-full cursor-pointer py-sb-3 pr-[10px] text-left hover:bg-sb-canvas-night-soft focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-sb-primary md:py-0",
        )}
      >
        <ChevronIcon open={isOpen} />
        <span className="flex min-w-0 items-center gap-sb-2">
          <span className="shrink-0 font-sb-mono font-medium text-sb-ink">
            {language.languageCode}
          </span>
          <span className="truncate text-sb-caption text-sb-ink-mute">{language.displayName}</span>
          {language.isSufficientSample ? null : (
            <span className="shrink-0 rounded-sb-tag bg-sb-tint-amber px-sb-2 text-sb-caption text-sb-amber-text">
              표본 부족
            </span>
          )}
        </span>
        <span className="col-start-2 flex items-center gap-[10px] md:col-auto">
          <span className="text-sb-caption text-sb-ink-mute md:hidden">비중</span>
          <Bar
            value={language.reviewShare}
            fillClass="fill-sb-hairline-strong"
            className="h-[10px] w-[160px] shrink-0"
          />
          <span className="font-sb-mono text-sb-ink tabular-nums">
            {formatPercent(language.reviewShare)}
          </span>
        </span>
        <span className="col-start-2 flex items-center gap-sb-3 md:col-auto">
          <span className="text-sb-caption text-sb-ink-mute md:hidden">긍정률</span>
          <Bar
            value={language.positiveRate}
            fillClass="fill-sb-primary-deep"
            className="h-[14px] min-w-0 flex-1"
            gridlines
          />
          <span className="w-[92px] shrink-0 font-sb-mono text-sb-lead font-medium text-sb-ink tabular-nums">
            {formatPercent(language.positiveRate)}
          </span>
        </span>
      </button>
      {isOpen ? (
        <div id={panelId}>
          <LanguageDetail gameId={gameId} languageCode={language.languageCode} />
        </div>
      ) : null}
    </li>
  )
}
