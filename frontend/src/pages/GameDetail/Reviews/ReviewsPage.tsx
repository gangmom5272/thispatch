import { useState } from "react"
import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import {
  useRepresentativeReviews,
  useReviewsInfinite,
  useReviewsTotalCount,
} from "@/hooks/queries/reviewQueries"
import { RepresentativeReviewsSection } from "@/pages/GameDetail/Reviews/components/RepresentativeReviewsSection"
import { ReviewList } from "@/pages/GameDetail/Reviews/components/ReviewList"
import { TopicFilterBar } from "@/pages/GameDetail/Reviews/components/TopicFilterBar"

/** URL gameId 세그먼트를 양의 정수로 파싱한다. */
function parseGameId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id >= 1 ? id : null
}

/**
 * 리뷰 탭 페이지.
 * 대표 리뷰 + 토픽 다중 선택(OR) 목록을 조립한다.
 */
export default function ReviewsPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  // selectedTopicIds: 다중 선택 토픽(OR). 비어 있으면 전체.
  const [selectedTopicIds, setSelectedTopicIds] = useState<number[]>([])

  const representativeQuery = useRepresentativeReviews(gameId)
  const listQuery = useReviewsInfinite(gameId, selectedTopicIds)
  const totalQuery = useReviewsTotalCount(gameId)

  if (gameId === null) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        잘못된 게임 주소입니다.
      </p>
    )
  }

  if (representativeQuery.isPending && !representativeQuery.data) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-ink-mute md:px-sb-12">
        리뷰를 불러오는 중…
      </p>
    )
  }

  if (representativeQuery.isError && !representativeQuery.data) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-neg-text md:px-sb-12">
        {isApiError(representativeQuery.error)
          ? representativeQuery.error.message
          : "리뷰를 불러오지 못했습니다."}
      </p>
    )
  }

  const reviews = representativeQuery.data ?? []
  // listItems: 무한 스크롤 페이지를 평탄화한 목록
  const listItems = listQuery.data?.pages.flatMap((page) => page.items) ?? []
  // period: 첫 페이지 meta 기준 데이터 구간
  const period = listQuery.data?.pages[0]?.meta.period ?? null
  // filteredCount: 현재 필터의 totalCount
  const filteredCount = listQuery.data?.pages[0]?.page.totalCount ?? null
  const totalCount = totalQuery.data ?? null

  /** 토픽 칩을 토글한다(이미 있으면 제거, 없으면 추가). */
  function handleToggleTopic(topicId: number) {
    setSelectedTopicIds((prev) =>
      prev.includes(topicId) ? prev.filter((id) => id !== topicId) : [...prev, topicId],
    )
  }

  return (
    <div className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <RepresentativeReviewsSection reviews={reviews} />

      <TopicFilterBar
        selectedTopicIds={selectedTopicIds}
        onToggleTopic={handleToggleTopic}
        onClear={() => setSelectedTopicIds([])}
        filteredCount={filteredCount}
        totalCount={totalCount}
      />

      {listQuery.isPending && !listQuery.data ? (
        <p className="text-sb-body text-sb-ink-mute">리뷰 목록을 불러오는 중…</p>
      ) : listQuery.isError && !listQuery.data ? (
        <p className="text-sb-body text-sb-neg-text">
          {isApiError(listQuery.error)
            ? listQuery.error.message
            : "리뷰 목록을 불러오지 못했습니다."}
        </p>
      ) : (
        <ReviewList
          items={listItems}
          period={period}
          hasNextPage={Boolean(listQuery.hasNextPage)}
          isFetchingNextPage={listQuery.isFetchingNextPage}
          isFetchNextPageError={listQuery.isFetchNextPageError}
          onLoadMore={() => {
            void listQuery.fetchNextPage()
          }}
        />
      )}
    </div>
  )
}
