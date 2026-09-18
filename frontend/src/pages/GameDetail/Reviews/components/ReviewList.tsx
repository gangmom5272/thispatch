import type { Review } from "@/types/review"
import { ReviewListCard } from "@/pages/GameDetail/Reviews/components/ReviewListCard"
import LoadMoreSentinel from "@/components/LoadMoreSentinel"

interface ReviewListProps {
  items: Review[]
  /** 검색에서 선택된 토픽 id — 카드 태그 강조용 */
  selectedTopicIds: number[]
  hasNextPage: boolean
  isFetchingNextPage: boolean
  isFetchNextPageError: boolean
  onLoadMore: () => void
}

/**
 * 필터된 리뷰 목록 + 고지 문구 + 무한 스크롤 센티널.
 */
export function ReviewList({
  items,
  selectedTopicIds,
  hasNextPage,
  isFetchingNextPage,
  isFetchNextPageError,
  onLoadMore,
}: ReviewListProps) {
  return (
    <section className="flex flex-col gap-sb-4">
      {items.length === 0 ? (
        <p className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4 text-sb-body text-sb-ink-mute">
          조건에 맞는 리뷰가 없습니다. 토픽 선택을 바꿔 보세요.
        </p>
      ) : (
        <ul className="flex flex-col gap-sb-3">
          {items.map((review) => (
            <li key={review.id}>
              <ReviewListCard review={review} selectedTopicIds={selectedTopicIds} />
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
        <LoadMoreSentinel
          key={items.length}
          isLoading={isFetchingNextPage}
          loadingLabel="다음 리뷰를 불러오는 중…"
          onReach={() => {
            if (!isFetchingNextPage) onLoadMore()
          }}
        />
      ) : null}
    </section>
  )
}
