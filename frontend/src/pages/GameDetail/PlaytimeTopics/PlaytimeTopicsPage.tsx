import { useState } from "react"
import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import { usePlaytimeTopics } from "@/hooks/queries/statisticsQueries"
import { BandCards } from "@/pages/GameDetail/PlaytimeTopics/components/BandCards"
import { TopicBars } from "@/pages/GameDetail/PlaytimeTopics/components/TopicBars"
import { formatDisplayRange } from "@/lib/seoulDate"

/** URL gameId 세그먼트를 양의 정수로 파싱한다. */
function parseGameId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id >= 1 ? id : null
}

/**
 * 플레이타임 × 토픽 탭 페이지.
 * 밴드 선택(null=전체)에 따라 playtime-topics API를 재조회한다.
 */
export default function PlaytimeTopicsPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  // selectedBandNo: null이면 전체(ALL), 1~4면 해당 밴드
  const [selectedBandNo, setSelectedBandNo] = useState<number | null>(null)

  const query = usePlaytimeTopics(gameId, selectedBandNo)

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
        플레이타임 × 토픽을 불러오는 중…
      </p>
    )
  }

  if (query.isError && !query.data) {
    return (
      <p className="px-sb-4 py-sb-8 text-sb-body text-sb-neg-text md:px-sb-12">
        {isApiError(query.error) ? query.error.message : "플레이타임 × 토픽을 불러오지 못했습니다."}
      </p>
    )
  }

  const data = query.data
  if (!data) return null

  const period = data.meta.period
  // selectedReviewCount: 현재 선택 구간의 리뷰 수(전체면 overall)
  const selectedReviewCount =
    selectedBandNo === null
      ? data.overall.reviewCount
      : (data.bands.find((band) => band.band === `B${selectedBandNo}`)?.reviewCount ??
        data.overall.reviewCount)

  return (
    <div className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <header>
        <h1 className="text-sb-title font-medium text-sb-ink">플레이타임 구간별 토픽</h1>
        <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
          데이터 구간 {formatDisplayRange(period.startDate, period.endDate, period.dayCount)} · 오늘
          기준 최근 {period.dayCount}일 · 리뷰 {data.overall.reviewCount.toLocaleString("en-US")}건
        </p>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
          구간 경계는 게임 전체 리뷰 p25·중앙·p75(
          {data.scale.p25Minutes}/{data.scale.medianMinutes}/{data.scale.p75Minutes}분) 기준입니다.
        </p>
      </header>

      <BandCards data={data} selectedBandNo={selectedBandNo} onSelectBandNo={setSelectedBandNo} />

      {data.sampleSufficient ? (
        <TopicBars
          topics={data.topics}
          reviewCount={selectedReviewCount}
          isOverall={selectedBandNo === null}
        />
      ) : null}
    </div>
  )
}
