import { useState } from "react"
import { TranslationErrorNotice } from "@/components/TranslationErrorNotice"
import { useReviewTranslation } from "@/hooks/queries/reviewQueries"
import type { Review } from "@/types/review"
import { formatPlaytimeMinutes } from "@/pages/GameDetail/PlaytimeTopics/lib/format"
import { resolveReviewBody } from "@/pages/GameDetail/Reviews/lib/resolveReviewBody"
import { ReviewBodyExpandable } from "@/pages/GameDetail/Reviews/components/ReviewBodyExpandable"

interface RepresentativeReviewCardProps {
  review: Review
}

/**
 * 최근 대표 리뷰 그리드용 카드.
 * 「번역」 클릭 시에만 번역 API를 조회하고, 본문은 2줄 클램프·더보기를 쓴다.
 */
export function RepresentativeReviewCard({ review }: RepresentativeReviewCardProps) {
  // showOriginal: true면 원문(body), false면 번역 API 결과
  const [showOriginal, setShowOriginal] = useState(true)
  // wantsTranslation: 번역 탭이 선택된 뒤에만 조회
  const wantsTranslation = !showOriginal
  const translationQuery = useReviewTranslation(review.id, wantsTranslation)
  const isNegative = review.sentiment === "NEGATIVE"
  // channelLabel: isUpdated면 수정본, 아니면 첫 작성
  const channelLabel = review.isUpdated ? "수정" : "첫 작성"
  // showTranslationError: 번역 탭에서만 에러 UI
  const showTranslationError = wantsTranslation && translationQuery.isError
  // displayBody: 원문 또는 번역/로딩 문구
  const displayBody = resolveReviewBody(
    review.body,
    translationQuery.data?.translatedText,
    showOriginal,
    translationQuery.isPending,
  )

  return (
    <article className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas p-sb-4">
      <div className="flex items-start justify-between gap-sb-2">
        <div className="flex min-w-0 flex-wrap items-center gap-sb-2">
          <span
            className={
              isNegative
                ? "rounded-sb-tag border border-sb-line-red bg-sb-tint-red px-sb-2 py-0.5 text-sb-caption text-sb-neg-text"
                : "rounded-sb-tag border border-sb-line-green bg-sb-tint-green px-sb-2 py-0.5 text-sb-caption text-sb-pos-text"
            }
          >
            {isNegative ? "부정" : "긍정"}
          </span>
          <span className="font-sb-mono text-sb-caption text-sb-ink-mute">
            {channelLabel} · {formatPlaytimeMinutes(review.playtimeMinutes)} · {review.languageCode}
          </span>
          <span className="font-sb-mono text-sb-caption tabular-nums text-sb-ink-mute">
            ♥ {review.helpfulCount.toLocaleString("en-US")}
          </span>
        </div>
        <div className="flex shrink-0 gap-sb-1">
          <button
            type="button"
            onClick={() => setShowOriginal(false)}
            className={
              !showOriginal
                ? "h-sb-control cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-3 text-sb-caption text-sb-ink"
                : "h-sb-control cursor-pointer rounded-sb-control px-sb-3 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
            }
          >
            번역
          </button>
          <button
            type="button"
            onClick={() => setShowOriginal(true)}
            className={
              showOriginal
                ? "h-sb-control cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-3 text-sb-caption text-sb-ink"
                : "h-sb-control cursor-pointer rounded-sb-control px-sb-3 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
            }
          >
            원문
          </button>
        </div>
      </div>

      {showTranslationError ? (
        <div className="mt-sb-3">
          <TranslationErrorNotice error={translationQuery.error} />
        </div>
      ) : (
        <ReviewBodyExpandable key={displayBody} text={displayBody} />
      )}

      {review.tags.length > 0 ? (
        <ul className="mt-sb-3 flex flex-wrap gap-sb-2">
          {review.tags.map((tag) => (
            <li
              key={tag.id}
              className="rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2 py-0.5 text-sb-caption text-sb-ink-mute"
            >
              {tag.name}
            </li>
          ))}
        </ul>
      ) : null}
    </article>
  )
}
