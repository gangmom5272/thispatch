import type { PlaytimeBandStats, PlaytimeTopics } from "@/types/statistics"
import {
  bandIdToBandNo,
  formatBandRangeLabel,
  formatPositiveRate,
} from "@/pages/GameDetail/PlaytimeTopics/lib/format"

interface BandCardsProps {
  data: PlaytimeTopics
  /** 현재 선택: null=전체(4구간 합계) */
  selectedBandNo: number | null
  onSelectBandNo: (bandNo: number | null) => void
}

/**
 * 전체 요약 카드 + 플레이타임 4구간 카드를 렌더한다.
 * 클릭 시 bandNo를 올려 부모에서 재조회한다.
 */
export function BandCards({ data, selectedBandNo, onSelectBandNo }: BandCardsProps) {
  const overallSelected = selectedBandNo === null

  return (
    <div className="grid gap-sb-3 sm:grid-cols-2 lg:grid-cols-5">
      <OverallCard
        overall={data.overall}
        selected={overallSelected}
        onSelect={() => onSelectBandNo(null)}
      />
      {data.bands.map((band) => {
        // bandNo: API 쿼리용 1~4
        const bandNo = bandIdToBandNo(band.band)
        const selected = bandNo !== null && bandNo === selectedBandNo
        return (
          <BandCard
            key={band.band}
            band={band}
            selected={selected}
            onSelect={() => {
              if (bandNo !== null) onSelectBandNo(bandNo)
            }}
          />
        )
      })}
    </div>
  )
}

interface OverallCardProps {
  overall: PlaytimeBandStats
  selected: boolean
  onSelect: () => void
}

/** 긍정·부정 건수를 라벨과 함께 표시한다. */
function SentimentCounts({
  positiveCount,
  negativeCount,
}: {
  positiveCount: number
  negativeCount: number
}) {
  return (
    <div className="mt-sb-2 flex flex-col gap-0.5 font-sb-mono text-sb-caption tabular-nums">
      <span className="text-sb-pos-text">긍정 {positiveCount.toLocaleString("en-US")}</span>
      <span className="text-sb-neg-text">부정 {negativeCount.toLocaleString("en-US")}</span>
    </div>
  )
}

/** 4구간 합계(전체) 카드. */
function OverallCard({ overall, selected, onSelect }: OverallCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`cursor-pointer rounded-sb-card border p-sb-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary ${
        selected
          ? "border-sb-primary bg-sb-canvas-active"
          : "border-sb-hairline-cool bg-sb-canvas-surface hover:bg-sb-canvas-soft"
      }`}
    >
      <p className="text-sb-caption text-sb-ink-mute">4구간 합계</p>
      <p className="mt-sb-2 font-sb-mono text-sb-title tabular-nums text-sb-ink">
        {formatPositiveRate(overall.positiveRate)}
      </p>
      <SentimentCounts
        positiveCount={overall.positiveCount}
        negativeCount={overall.negativeCount}
      />
      <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">
        최근 14일 {overall.reviewCount.toLocaleString("en-US")}건
      </p>
    </button>
  )
}

interface BandCardProps {
  band: PlaytimeBandStats
  selected: boolean
  onSelect: () => void
}

/** 개별 플레이타임 밴드 카드. */
function BandCard({ band, selected, onSelect }: BandCardProps) {
  const insufficient = !band.isSufficientSample
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`cursor-pointer rounded-sb-card border p-sb-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary ${
        selected
          ? "border-sb-primary bg-sb-canvas-active"
          : "border-sb-hairline-cool bg-sb-canvas-surface hover:bg-sb-canvas-soft"
      }`}
    >
      <p className="text-sb-caption text-sb-ink-mute">{formatBandRangeLabel(band)}</p>
      {insufficient ? (
        <>
          <p className="mt-sb-2 text-sb-title text-sb-amber-text">—</p>
          <p className="mt-sb-1 text-sb-caption text-sb-neg-text">
            표본 부족 {band.reviewCount.toLocaleString("en-US")}건
          </p>
        </>
      ) : (
        <>
          <p className="mt-sb-2 font-sb-mono text-sb-title tabular-nums text-sb-ink">
            {formatPositiveRate(band.positiveRate)}
          </p>
          <SentimentCounts positiveCount={band.positiveCount} negativeCount={band.negativeCount} />
          <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">
            표본 충족 · {band.reviewCount.toLocaleString("en-US")}건
          </p>
        </>
      )}
    </button>
  )
}
