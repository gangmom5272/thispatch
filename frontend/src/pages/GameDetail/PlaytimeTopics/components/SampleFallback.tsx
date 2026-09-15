import type { PlaytimeBandStats, PlaytimeTopicsFallback } from "@/types/statistics"
import {
  formatBandRangeLabel,
  formatPlaytimeMinutes,
} from "@/pages/GameDetail/PlaytimeTopics/lib/format"
import { formatShortMd } from "@/lib/seoulDate"

interface SampleFallbackProps {
  fallback: PlaytimeTopicsFallback
  /** 표본 부족인 밴드 통계(라벨·건수 표시용) */
  band: PlaytimeBandStats | undefined
  minimumSampleCount: number
}

/**
 * 표본 부족 시 토픽·AI 대신 리뷰 원문 리스트를 보여준다.
 */
export function SampleFallback({ fallback, band, minimumSampleCount }: SampleFallbackProps) {
  const bandLabel = band ? formatBandRangeLabel(band) : "선택 구간"
  // items: 선택 밴드의 원문(보통 1개 밴드만 채워짐)
  const items = fallback.itemsByBand.flatMap((group) => group.items)

  return (
    <section className="rounded-sb-card border border-sb-line-amber bg-sb-tint-amber p-sb-4">
      <header className="mb-sb-4">
        <h2 className="text-sb-title font-medium text-sb-amber-text">리뷰 원문</h2>
        <p className="mt-sb-1 text-sb-body text-sb-ink">
          {bandLabel} · 최근 14일 {fallback.totalCount}건 전체 · 작성일 순
        </p>
        <p className="mt-sb-2 text-sb-body leading-relaxed text-sb-ink-mute">
          {fallback.totalCount}건 전체입니다. 표본이 적어 이 구간의 수치는 진단 근거로 쓰지 않으며,
          기획안 진단으로 넘길 때도 포함되지 않습니다. 토픽 분포 산출 최소 표본은{" "}
          {minimumSampleCount}건입니다. 구간을 넓히거나 다른 플레이타임 구간을 선택하세요.
        </p>
        <p className="mt-sb-2 text-sb-caption text-sb-ink-mute">{fallback.message}</p>
      </header>

      {items.length === 0 ? (
        <p className="text-sb-body text-sb-ink-mute">표시할 원문이 없습니다.</p>
      ) : (
        <ul className="flex flex-col gap-sb-3">
          {items.map((review) => (
            <li
              key={review.id}
              className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas p-sb-3"
            >
              <p className="font-sb-mono text-sb-caption text-sb-ink-mute">
                {formatShortMd(review.reviewDate)} · {formatPlaytimeMinutes(review.playtimeMinutes)}{" "}
                · {review.languageCode}
                <span
                  className={
                    review.sentiment === "NEGATIVE"
                      ? "ml-sb-2 text-sb-neg-text"
                      : "ml-sb-2 text-sb-pos-text"
                  }
                >
                  {review.sentiment === "NEGATIVE" ? "부정" : "긍정"}
                </span>
              </p>
              <p className="mt-sb-2 text-sb-body leading-relaxed text-sb-ink">{review.body}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
