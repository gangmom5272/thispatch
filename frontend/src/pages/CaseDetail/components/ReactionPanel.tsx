import RateBar from "@/components/RateBar"
import { formatDeltaPp, formatPercent } from "@/lib/format"
import type { SimilarCase } from "@/types"

function deltaTone(delta: number): { text: string; chip: string; legend: string } {
  if (delta > 0) {
    return {
      text: "text-sb-pos-text",
      chip: "bg-sb-tint-green text-sb-pos-text",
      legend: "bg-sb-pos",
    }
  }
  if (delta < 0) {
    return {
      text: "text-sb-neg-text",
      chip: "bg-sb-tint-red text-sb-neg-text",
      legend: "bg-sb-neg",
    }
  }
  return {
    text: "text-sb-ink",
    chip: "bg-sb-canvas-soft text-sb-ink-mute",
    legend: "bg-sb-hairline-strong",
  }
}

function formatDays(days: number | null): string {
  return days === null ? "정보 없음" : `${days.toFixed(1)}일`
}

function formatFollowUp(item: SimilarCase): string {
  if (item.nextPatchIntervalDays === null || item.followUpSpeedRatio === null) {
    return "정보 없음"
  }
  return `${item.nextPatchIntervalDays}일 · 평소 주기의 ${item.followUpSpeedRatio.toFixed(1)}배`
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-sb-2">
      <dt className="text-sb-ink-mute">{label}</dt>
      <dd className="font-sb-mono font-medium tabular-nums">{value}</dd>
    </div>
  )
}

export default function ReactionPanel({ item }: { item: SimilarCase }) {
  const tone = deltaTone(item.deltaPp)
  const kept = Math.min(item.positiveRateBefore, item.positiveRateAfter)
  const changed = Math.abs(item.deltaPp)
  const changeLabel = item.deltaPp >= 0 ? "상승분" : "하락분"

  return (
    <section
      aria-labelledby="reaction-heading"
      className="flex flex-col rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
    >
      <div className="flex flex-wrap items-baseline gap-x-sb-2 gap-y-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
        <h2 id="reaction-heading" className="font-medium">
          패치 당시 리뷰 반응
        </h2>
        <p className="font-sb-mono text-sb-ink-mute">작성일 기준</p>
      </div>
      <div className="flex flex-col gap-sb-3 p-sb-4">
        <p className="flex gap-sb-2 rounded-sb-control border border-sb-hairline bg-sb-canvas-night-soft px-sb-3 py-sb-2 text-sb-ink-mute">
          <span aria-hidden="true">ⓘ</span>
          운영 조건은 성공·실패 요인이 아니라 당시 관측된 배경 정보입니다. 공개 덤프 기반 과거
          사례에는 수정 시각이 없어 작성일 기준으로만 집계됩니다.
        </p>

        <div className="flex flex-wrap items-baseline gap-sb-3 font-sb-mono tabular-nums">
          <span className="text-sb-title text-sb-ink-mute">
            {formatPercent(item.positiveRateBefore)}
          </span>
          <span aria-hidden="true" className="text-sb-ink-mute">
            →
          </span>
          <span className={`text-sb-section font-medium ${tone.text}`}>
            {formatPercent(item.positiveRateAfter)}
          </span>
          <span className={`ml-auto rounded-sb-tag px-sb-2 py-sb-1 font-medium ${tone.chip}`}>
            {formatDeltaPp(item.deltaPp)}
          </span>
        </div>
        <p>
          {formatDeltaPp(item.deltaPp)}는 관측된 변화입니다. 이 패치가 원인임을 뜻하지 않습니다.
        </p>

        <div className="flex flex-col gap-sb-2">
          <div className="flex items-center gap-sb-3">
            <span className="w-[74px] shrink-0 text-sb-ink-mute">긍정률</span>
            <RateBar
              before={item.positiveRateBefore}
              after={item.positiveRateAfter}
              className="h-sb-3"
            />
          </div>
          <ul className="flex flex-wrap gap-x-sb-4 gap-y-sb-1 pl-[86px] text-sb-ink-mute">
            <li className="flex items-center gap-sb-2">
              <span aria-hidden="true" className="size-[11px] rounded-sm bg-sb-hairline-strong" />
              패치 후 유지 {formatPercent(kept)}
            </li>
            <li className="flex items-center gap-sb-2">
              <span aria-hidden="true" className={`size-[11px] rounded-sm ${tone.legend}`} />
              {changeLabel} {changed.toFixed(1)}%p
            </li>
          </ul>
        </div>

        <hr className="border-sb-hairline" />

        <dl className="flex flex-col gap-sb-2">
          <MetaRow label="분석 대상 리뷰" value={`${item.reviewCount.toLocaleString("en-US")}건`} />
          <MetaRow label="패치 시점" value={item.patchedOn} />
          <MetaRow label="평소 패치 주기" value={formatDays(item.avgPatchIntervalDays)} />
        </dl>

        <hr className="border-sb-hairline" />

        <h3 className="font-medium text-sb-ink-mute">당시 관측된 운영 조건</h3>
        <dl className="flex flex-wrap items-baseline gap-sb-2">
          <dt className="text-sb-ink-mute">후속 패치까지</dt>
          <dd className="font-medium text-sb-amber-text tabular-nums">{formatFollowUp(item)}</dd>
        </dl>
      </div>
    </section>
  )
}
