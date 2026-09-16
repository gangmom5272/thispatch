import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import { useRepresentativeReviews } from "@/hooks/queries/reviewQueries"
import { RepresentativeReviewsSection } from "@/pages/GameDetail/Reviews/components/RepresentativeReviewsSection"

/** URL gameId 세그먼트를 양의 정수로 파싱한다. */
function parseGameId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id >= 1 ? id : null
}

/**
 * 리뷰 탭 페이지.
 * 121: 최근 대표 리뷰. 122에서 토픽 필터·목록을 이어서 조립한다.
 */
export default function ReviewsPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  const query = useRepresentativeReviews(gameId)

  if (gameId === null) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        잘못된 게임 주소입니다.
      </p>
    )
  }

  if (query.isPending && !query.data) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        리뷰를 불러오는 중…
      </p>
    )
  }

  if (query.isError && !query.data) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-neg-text md:px-sb-12">
        {isApiError(query.error) ? query.error.message : "리뷰를 불러오지 못했습니다."}
      </p>
    )
  }

  const reviews = query.data ?? []

  return (
    <div className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <RepresentativeReviewsSection reviews={reviews} />
    </div>
  )
}
