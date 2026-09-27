import type { Review } from "@/types/review"
import { RepresentativeReviewCard } from "@/pages/GameDetail/Reviews/components/RepresentativeReviewCard"

interface RepresentativeReviewsSectionProps {
  reviews: Review[]
}

/** 「최근 대표 리뷰」 제목 + 2열 카드 그리드. items-start로 한 카드를 펼쳐도 옆 카드는 늘어나지 않는다. */
export function RepresentativeReviewsSection({ reviews }: RepresentativeReviewsSectionProps) {
  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-4">
        <h2 className="text-sb-title font-medium text-sb-ink">최근 대표 리뷰</h2>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
          최근 14일 리뷰 중 유저가 선정한 대표 리뷰
        </p>
      </header>

      {reviews.length === 0 ? (
        <p className="text-sb-body text-sb-ink-mute">표시할 대표 리뷰가 없습니다.</p>
      ) : (
        <ul className="grid items-start gap-sb-3 md:grid-cols-2">
          {reviews.map((review) => (
            <li key={review.id}>
              <RepresentativeReviewCard review={review} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
