import { useLayoutEffect, useRef, useState } from "react"
import { cn } from "@/lib/cn"
import type { RepresentativeReview, ReviewSentiment } from "@/types"

type ReviewView = "translated" | "original"

const sentimentTone: Record<ReviewSentiment, { label: string; chip: string }> = {
  POSITIVE: { label: "긍정", chip: "bg-sb-tint-green text-sb-pos-text" },
  NEGATIVE: { label: "부정", chip: "bg-sb-tint-red text-sb-neg-text" },
}

function formatReviewMeta(review: RepresentativeReview): string {
  const playtime =
    review.playtimeMinutes === null
      ? "플레이타임 정보 없음"
      : `${Math.round(review.playtimeMinutes / 60)}h`
  const monthDay = review.reviewDate.slice(5).replace("-", "/")
  return `${playtime} · ${monthDay}`
}

function SegmentButton({
  active,
  disabled,
  onClick,
  children,
}: {
  active: boolean
  disabled?: boolean
  onClick: () => void
  children: string
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "h-[27px] cursor-pointer px-[9px] text-sb-body focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50",
        active ? "bg-sb-canvas font-medium text-sb-ink" : "text-sb-ink-mute hover:text-sb-ink",
      )}
    >
      {children}
    </button>
  )
}

/** 2줄을 넘는 본문만 접어 두고 더보기/접기 버튼을 보여준다. */
function ClampedBody({ text, lang }: { text: string; lang?: string }) {
  const bodyRef = useRef<HTMLParagraphElement>(null)
  const [expanded, setExpanded] = useState(false)
  const [overflows, setOverflows] = useState(false)

  useLayoutEffect(() => {
    const el = bodyRef.current
    if (!expanded && el) {
      setOverflows(el.scrollHeight > el.clientHeight)
    }
  }, [text, expanded])

  return (
    <>
      <p
        ref={bodyRef}
        lang={lang}
        className={cn("leading-relaxed text-sb-ink", !expanded && "line-clamp-2")}
      >
        {text}
      </p>
      {expanded || overflows ? (
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => setExpanded((prev) => !prev)}
          className={cn(
            "w-fit cursor-pointer rounded-sb-tag text-sb-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary",
            expanded
              ? "text-sb-ink-mute hover:text-sb-ink"
              : "text-sb-primary hover:text-sb-primary-soft",
          )}
        >
          {expanded ? "접기" : "···더보기"}
        </button>
      ) : null}
    </>
  )
}

export default function ReviewCard({ review }: { review: RepresentativeReview }) {
  const [view, setView] = useState<ReviewView>("original")
  const tone = sentimentTone[review.sentiment]
  const showOriginal = view === "original" || !review.translatedBody
  const text = showOriginal ? review.body : (review.translatedBody ?? review.body)

  return (
    <article className="flex flex-col gap-sb-2 rounded-sb-control bg-sb-canvas-surface px-[15px] py-[13px]">
      <div className="flex flex-wrap items-center gap-[10px]">
        <span className={`rounded-sb-tag px-sb-2 py-0.5 font-medium ${tone.chip}`}>
          {tone.label}
        </span>
        <span className="font-sb-mono text-sb-ink-mute tabular-nums">
          {formatReviewMeta(review)}
        </span>
        <div
          role="group"
          aria-label="리뷰 표시 언어"
          className="ml-auto inline-flex overflow-hidden rounded-sb-control border border-sb-hairline-cool bg-sb-canvas-soft"
        >
          <SegmentButton
            active={!showOriginal}
            disabled={!review.translatedBody}
            onClick={() => setView("translated")}
          >
            번역
          </SegmentButton>
          <span aria-hidden="true" className="w-px bg-sb-hairline-cool" />
          <SegmentButton active={showOriginal} onClick={() => setView("original")}>
            원문
          </SegmentButton>
        </div>
      </div>
      <ClampedBody text={text} lang={showOriginal ? undefined : "ko"} />
      <p className="font-sb-mono text-sb-ink-mute tabular-nums">
        도움됨 {review.helpfulCount.toLocaleString("en-US")}
        {review.tags.length > 0 ? ` · ${review.tags.map((tag) => tag.name).join(", ")}` : ""}
      </p>
    </article>
  )
}
