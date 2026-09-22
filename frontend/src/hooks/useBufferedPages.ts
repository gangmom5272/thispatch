import { useEffect, useState } from "react"

/** 화면에 노출한 페이지보다 몇 페이지 앞까지 캐시에 채워 둘지 */
const PREFETCH_PAGE_COUNT = 2

interface InfinitePagesSource {
  hasNextPage: boolean
  isFetchingNextPage: boolean
  isFetchNextPageError: boolean
  fetchNextPage: () => unknown
}

/**
 * 무한 스크롤에서 화면에 노출할 페이지 수를 직접 관리하고,
 * 캐시가 항상 PREFETCH_PAGE_COUNT만큼 앞서도록 다음 페이지를 미리 받아 둔다.
 * 스크롤이 바닥에 닿으면 이미 받아 둔 페이지를 곧바로 보여 준다.
 * resetKey가 바뀌면(검색·필터 변경) 다시 첫 페이지만 노출한다.
 */
export function useBufferedPages(
  loadedPageCount: number,
  query: InfinitePagesSource,
  resetKey: string,
) {
  const [visiblePageCount, setVisiblePageCount] = useState(1)
  const [appliedResetKey, setAppliedResetKey] = useState(resetKey)
  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query

  if (appliedResetKey !== resetKey) {
    setAppliedResetKey(resetKey)
    setVisiblePageCount(1)
  }

  useEffect(() => {
    if (loadedPageCount === 0 || !hasNextPage) return
    if (isFetchingNextPage || isFetchNextPageError) return
    if (loadedPageCount >= visiblePageCount + PREFETCH_PAGE_COUNT) return
    fetchNextPage()
  }, [
    loadedPageCount,
    visiblePageCount,
    hasNextPage,
    isFetchingNextPage,
    isFetchNextPageError,
    fetchNextPage,
  ])

  return {
    visiblePageCount,
    hasMore: visiblePageCount < loadedPageCount || hasNextPage,
    /** 노출을 요청한 페이지가 아직 도착하지 않은 상태 */
    isWaitingNextPage: visiblePageCount > loadedPageCount,
    showNextPage: () => setVisiblePageCount((count) => Math.min(count + 1, loadedPageCount + 1)),
  }
}
