import { useState } from "react"
import { TranslationErrorNotice } from "@/components/TranslationErrorNotice"
import { useReviewTranslation } from "@/hooks/queries/reviewQueries"
import type { Review } from "@/types/review"
import { formatPlaytimeMinutes } from "@/pages/GameDetail/PlaytimeTopics/lib/format"
import { formatShortMd } from "@/lib/seoulDate"
import { resolveReviewBody } from "@/pages/GameDetail/Reviews/lib/resolveReviewBody"
import { ReviewBodyExpandable } from "@/pages/GameDetail/Reviews/components/ReviewBodyExpandable"

interface ReviewListCardProps {
  review: Review
  /** 검색 칩에서 선택된 토픽 — 카드 태그에 강조 표시 */
  selectedTopicIds: number[]
}

/**
 * 필터 목록용 가로형 리뷰 카드.
 * 좌: 뱃지·날짜·본문·태그 / 우: 번역·원문·메타.
 * 「번역」 클릭 시에만 번역 API를 조회하고, 본문은 2줄 클램프·더보기를 쓴다.
 */
export function ReviewListCard({ review, selectedTopicIds }: ReviewListCardProps) {
  // showOriginal: true면 원문(body), false면 번역 API 결과
  const [showOriginal, setShowOriginal] = useState(true)
  // wantsTranslation: 번역 탭이 선택된 뒤에만 조회
  const wantsTranslation = !showOriginal
  const translationQuery = useReviewTranslation(review.id, wantsTranslation)
  const isNegative = review.sentiment === "NEGATIVE"
  // channelLabel: 작성 채널(수정 / 첫 작성)
  const channelLabel = review.isUpdated ? "수정" : "첫 작성"
  // hasFilter: 칩이 하나라도 선택된 상태
  const hasFilter = selectedTopicIds.length > 0
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
      <div className="flex flex-col gap-sb-4 md:flex-row md:gap-sb-6">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-sb-2">
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
              {channelLabel} · {formatShortMd(review.reviewDate)}
            </span>
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
              {review.tags.map((tag) => {
                // highlighted: 현재 선택된 검색 칩과 일치하는 태그
                const highlighted = hasFilter && selectedTopicIds.includes(tag.id)
                return (
                  <li
                    key={tag.id}
                    className={
                      highlighted
                        ? "rounded-sb-tag border border-sb-primary bg-sb-canvas-active px-sb-2 py-0.5 text-sb-caption font-medium text-sb-ink"
                        : "rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2 py-0.5 text-sb-caption text-sb-ink-mute"
                    }
                  >
                    {tag.name}
                  </li>
                )
              })}
            </ul>
          ) : null}
        </div>

        <aside className="flex w-full shrink-0 flex-col gap-sb-3 border-t border-sb-hairline-cool pt-sb-3 md:w-44 md:border-l md:border-t-0 md:pl-sb-4 md:pt-0">
          <div className="flex gap-sb-1">
            <button
              type="button"
              onClick={() => setShowOriginal(false)}
              className={
                !showOriginal
                  ? "h-sb-control flex-1 cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-2 text-sb-caption text-sb-ink"
                  : "h-sb-control flex-1 cursor-pointer rounded-sb-control px-sb-2 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
              }
            >
              번역
            </button>
            <button
              type="button"
              onClick={() => setShowOriginal(true)}
              className={
                showOriginal
                  ? "h-sb-control flex-1 cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-2 text-sb-caption text-sb-ink"
                  : "h-sb-control flex-1 cursor-pointer rounded-sb-control px-sb-2 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
              }
            >
              원문
            </button>
          </div>

          <dl className="grid grid-cols-[auto_1fr] gap-x-sb-3 gap-y-sb-2 text-sb-caption">
            <dt className="text-sb-ink-mute">도움됨</dt>
            <dd className="font-sb-mono tabular-nums text-sb-ink">
              {review.helpfulCount.toLocaleString("en-US")}
            </dd>
            <dt className="text-sb-ink-mute">플레이 이력</dt>
            <dd className="font-sb-mono text-sb-ink">
              {formatPlaytimeMinutes(review.playtimeMinutes)}
            </dd>
            <dt className="text-sb-ink-mute">언어</dt>
            <dd className="font-sb-mono text-sb-ink">{review.languageCode}</dd>
          </dl>
        </aside>
      </div>
    </article>
  )
}
