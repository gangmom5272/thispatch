import type { Review } from "@/types/review"
import { ReviewListCard } from "@/pages/GameDetail/Reviews/components/ReviewListCard"
import { formatDisplayRange } from "@/lib/seoulDate"
import type { StatPeriod } from "@/types/statistics"

interface ReviewListProps {
  items: Review[]
  period: StatPeriod | null
  hasNextPage: boolean
  isFetchingNextPage: boolean
  isFetchNextPageError: boolean
  onLoadMore: () => void
}

/**
 * 필터된 리뷰 목록 + 고지 문구 + 더 보기.
 */
export function ReviewList({
  items,
  period,
  hasNextPage,
  isFetchingNextPage,
  isFetchNextPageError,
  onLoadMore,
}: ReviewListProps) {
  return (
    <section className="flex flex-col gap-sb-4">
      {period ? (
        <p className="font-sb-mono text-sb-caption text-sb-ink-mute">
          데이터 구간 {formatDisplayRange(period.startDate, period.endDate, period.dayCount)}
        </p>
      ) : null}

      <p className="text-sb-body leading-relaxed text-sb-ink-mute">
        토픽은 리뷰 문장에서 추출한 분류이며 작성자의 의도를 단정하지 않습니다. 여러 토픽을 선택하면
        하나라도 해당하는 리뷰를 보여줍니다.
      </p>

      {items.length === 0 ? (
        <p className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4 text-sb-body text-sb-ink-mute">
          조건에 맞는 리뷰가 없습니다. 토픽 선택을 바꿔 보세요.
        </p>
      ) : (
        <ul className="flex flex-col gap-sb-3">
          {items.map((review) => (
            <li key={review.id}>
              <ReviewListCard review={review} />
            </li>
          ))}
        </ul>
      )}

      {isFetchNextPageError ? (
        <div className="flex flex-col items-center gap-sb-2 py-sb-4">
          <p role="alert" className="text-sb-body text-sb-neg-text">
            다음 리뷰를 불러오지 못했습니다.
          </p>
          <button
            type="button"
            onClick={onLoadMore}
            className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            다시 시도
          </button>
        </div>
      ) : null}

      {hasNextPage && !isFetchNextPageError ? (
        <div className="flex justify-center py-sb-2">
          <button
            type="button"
            onClick={onLoadMore}
            disabled={isFetchingNextPage}
            className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-6 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isFetchingNextPage ? "불러오는 중…" : "더 보기"}
          </button>
        </div>
      ) : null}
    </section>
  )
}
