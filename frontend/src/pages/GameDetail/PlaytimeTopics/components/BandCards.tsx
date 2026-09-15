import type { PlaytimeBandStats, PlaytimeTopics } from "@/types/statistics"
import {
  bandIdToBandNo,
  formatBandRangeLabel,
  formatPositiveRate,
} from "@/pages/GameDetail/PlaytimeTopics/lib/format"

interface BandCardsProps {
  data: PlaytimeTopics
  /** 현재 선택: null=전체 보기 */
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
    <div className="flex flex-col gap-sb-3">
      <div className="flex flex-wrap items-center justify-between gap-sb-2">
        <p className="text-sb-caption text-sb-ink-mute">
          표본 {data.minimumSampleCount}건 미만 구간은 리뷰 원문으로 대체
        </p>
        <button
          type="button"
          className={`h-sb-control cursor-pointer rounded-sb-control px-sb-4 text-sb-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary ${
            overallSelected
              ? "bg-sb-primary text-sb-on-primary"
              : "border border-sb-hairline-strong bg-sb-canvas text-sb-ink hover:bg-sb-canvas-soft"
          }`}
          onClick={() => onSelectBandNo(null)}
        >
          전체 보기
        </button>
      </div>

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
    </div>
  )
}

interface OverallCardProps {
  overall: PlaytimeBandStats
  selected: boolean
  onSelect: () => void
}

/** 4구간 합계(전체) 카드. */
function OverallCard({ overall, selected, onSelect }: OverallCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`rounded-sb-card border p-sb-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary ${
        selected
          ? "border-sb-primary bg-sb-canvas-active"
          : "border-sb-hairline-cool bg-sb-canvas-surface hover:bg-sb-canvas-soft"
      }`}
    >
      <p className="text-sb-caption text-sb-ink-mute">4구간 합계</p>
      <p className="mt-sb-2 font-sb-mono text-sb-title tabular-nums text-sb-ink">
        {formatPositiveRate(overall.positiveRate)}
      </p>
      <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">긍정</p>
      <div className="mt-sb-2 flex gap-sb-4 font-sb-mono text-sb-caption tabular-nums text-sb-ink-mute">
        <span className="text-sb-pos-text">{overall.positiveCount}</span>
        <span className="text-sb-neg-text">{overall.negativeCount}</span>
      </div>
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
  const insufficient = !band.sampleSufficient
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`rounded-sb-card border p-sb-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary ${
        selected
          ? "border-sb-primary bg-sb-canvas-active"
          : "border-sb-hairline-cool bg-sb-canvas-surface hover:bg-sb-canvas-soft"
      }`}
    >
      <p className="text-sb-caption text-sb-ink-mute">{formatBandRangeLabel(band)}</p>
      {insufficient ? (
        <>
          <p className="mt-sb-2 text-sb-title text-sb-amber-text">—</p>
          <p className="mt-sb-1 text-sb-caption text-sb-amber-text">표본 부족</p>
          <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">{band.reviewCount}건</p>
        </>
      ) : (
        <>
          <p className="mt-sb-2 font-sb-mono text-sb-title tabular-nums text-sb-ink">
            {formatPositiveRate(band.positiveRate)}
          </p>
          <div className="mt-sb-2 flex gap-sb-4 font-sb-mono text-sb-caption tabular-nums">
            <span className="text-sb-pos-text">{band.positiveCount}</span>
            <span className="text-sb-neg-text">{band.negativeCount}</span>
          </div>
          <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">
            {band.sampleSufficient ? "표본 충족" : "표본 부족"} · {band.reviewCount}건
          </p>
        </>
      )}
    </button>
  )
}
