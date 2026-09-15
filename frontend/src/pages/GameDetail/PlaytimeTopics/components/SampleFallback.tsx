import type {
  PlaytimeBandStats,
  PlaytimeFallbackReview,
  PlaytimeTopicsFallback,
} from "@/types/statistics"
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
 * 표본 부족 시 노란 안내 + 리뷰 원문(2열 카드)을 보여준다.
 */
export function SampleFallback({ fallback, band, minimumSampleCount }: SampleFallbackProps) {
  const bandLabel = band ? formatBandRangeLabel(band) : "선택 구간"
  // items: 선택 밴드의 원문(보통 1개 밴드만 채워짐)
  const items = fallback.itemsByBand.flatMap((group) => group.items)

  return (
    <div className="flex flex-col gap-sb-4">
      <div className="flex gap-sb-3 rounded-sb-card border border-sb-line-amber bg-sb-tint-amber p-sb-4">
        <span className="mt-0.5 shrink-0 text-sb-amber-text" aria-hidden>
          ⚠
        </span>
        <div>
          <p className="text-sb-body font-medium leading-relaxed text-sb-amber-text">
            {bandLabel} 구간은 최근 14일 리뷰가 {fallback.totalCount}건이라 토픽 5종 분포를 만들지
            않았습니다.
          </p>
          <p className="mt-sb-2 text-sb-caption leading-relaxed text-sb-amber-text/90">
            표본 {minimumSampleCount}건 미만에서는 토픽 비중과 긍정·부정 비율이 리뷰 한두 건에 크게
            흔들립니다. 분포 대신 해당 구간의 리뷰 원문 전체를 그대로 보여줍니다. AI 대표 반응
            요약도 같은 이유로 생성하지 않습니다.
          </p>
        </div>
      </div>

      <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
        <header className="mb-sb-4">
          <h2 className="text-sb-title font-medium text-sb-ink">리뷰 원문</h2>
          <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
            {bandLabel} · 최근 14일 {fallback.totalCount}건 전체 · 작성일 순
          </p>
          <p className="mt-sb-2 text-sb-body leading-relaxed text-sb-ink-mute">
            {fallback.totalCount}건 전체입니다. 표본이 적어 이 구간의 수치는 진단 근거로 쓰지
            않으며, 기획안 진단으로 넘길 때도 포함되지 않습니다. 구간을 넓히거나 다른 플레이타임
            구간을 선택하세요.
          </p>
        </header>

        {items.length === 0 ? (
          <p className="text-sb-body text-sb-ink-mute">표시할 원문이 없습니다.</p>
        ) : (
          <ul className="grid gap-sb-3 md:grid-cols-2">
            {items.map((review) => (
              <FallbackReviewCard key={review.id} review={review} />
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** 표본 부족용 리뷰 카드(긍정/부정 뱃지 · 메타 · 도움됨 · 본문). */
function FallbackReviewCard({ review }: { review: PlaytimeFallbackReview }) {
  const isNegative = review.sentiment === "NEGATIVE"
  const helpful = review.helpfulCount ?? 0

  return (
    <li className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas p-sb-3">
      <div className="flex items-start justify-between gap-sb-2">
        <div className="flex min-w-0 flex-wrap items-center gap-sb-2">
          <span
            className={
              isNegative
                ? "rounded-sb-tag border border-sb-line-red bg-sb-tint-red px-sb-2 py-0.5 text-sb-caption text-sb-neg-text"
                : "rounded-sb-tag border border-sb-line-green bg-sb-tint-green px-sb-2 py-0.5 text-sb-caption text-sb-pos-text"
            }
          >
            {isNegative ? "부정" : "긍정"}
          </span>
          <span className="font-sb-mono text-sb-caption text-sb-ink-mute">
            {formatShortMd(review.reviewDate)} · {formatPlaytimeMinutes(review.playtimeMinutes)} ·{" "}
            {review.languageCode}
          </span>
        </div>
        {helpful > 0 ? (
          <span className="shrink-0 font-sb-mono text-sb-caption tabular-nums text-sb-ink-mute">
            ♥ {helpful.toLocaleString("en-US")}
          </span>
        ) : null}
      </div>
      <p className="mt-sb-2 text-sb-body leading-relaxed text-sb-ink">{review.body}</p>
    </li>
  )
}
