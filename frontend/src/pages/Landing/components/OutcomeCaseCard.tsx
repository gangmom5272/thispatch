import RateBar from "@/components/RateBar"
import { formatDeltaPp, formatPercent } from "@/lib/format"
import type { OutcomeCase, OutcomeGroup } from "@/pages/Landing/demoData"

const GROUP_TONE: Record<OutcomeGroup, string> = {
  negative: "border-sb-line-red bg-sb-tint-red text-sb-neg-text",
  neutral: "border-sb-hairline-strong bg-sb-canvas-soft text-sb-ink-mute",
  positive: "border-sb-line-green bg-sb-tint-green text-sb-pos-text",
}

function deltaTone(delta: number): string {
  if (delta > 0) return "text-sb-pos-text"
  if (delta < 0) return "text-sb-neg-text"
  return "text-sb-ink-mute"
}

function SummaryRow({ label, tone, text }: { label: string; tone: string; text: string }) {
  return (
    <div className="flex items-start gap-sb-2 text-sb-caption">
      <span className={`shrink-0 font-medium ${tone}`}>{label}</span>
      <p className="leading-relaxed text-sb-ink">{text}</p>
    </div>
  )
}

/**
 * 04 비교 섹션의 결과군 사례 카드.
 * 세 결과군의 크기와 강조 수준을 동일하게 두어 판단을 유도하지 않는다.
 */
export function OutcomeCaseCard({ item }: { item: OutcomeCase }) {
  const delta = item.positiveRateAfter - item.positiveRateBefore

  return (
    <article className="flex h-full flex-col gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <span
        className={`w-fit rounded-sb-tag border px-sb-2 py-px text-sb-caption ${GROUP_TONE[item.group]}`}
      >
        {item.groupLabel}
      </span>

      <div>
        <div className="flex items-baseline justify-between gap-sb-2">
          <h3 className="truncate text-sb-title font-medium text-sb-ink">{item.gameName}</h3>
          <p className="shrink-0 text-sb-caption text-sb-ink-mute">
            유사도{" "}
            <span className="font-sb-mono tabular-nums text-sb-primary">{item.similarity}</span>
          </p>
        </div>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">{item.description}</p>
        <p className="font-sb-mono text-sb-caption text-sb-ink-mute">{item.version}</p>
      </div>

      <div>
        <div className="flex items-baseline justify-between gap-sb-2 text-sb-caption">
          <span className="text-sb-ink-mute">긍정률</span>
          <span className="font-sb-mono tabular-nums text-sb-ink-mute">
            {formatPercent(item.positiveRateBefore)} →{" "}
            <span className={`font-medium ${deltaTone(delta)}`}>
              {formatPercent(item.positiveRateAfter)}
            </span>{" "}
            <span className={`font-medium ${deltaTone(delta)}`}>{formatDeltaPp(delta)}</span>
          </span>
        </div>
        <div className="mt-sb-2">
          <RateBar
            before={item.positiveRateBefore}
            after={item.positiveRateAfter}
            className="h-1.5"
          />
        </div>
      </div>

      <div className="font-sb-mono text-sb-caption text-sb-ink-mute">
        <p>{item.cadence}</p>
        <p>{item.followUp}</p>
      </div>

      <div className="mt-auto flex flex-col gap-sb-2 border-t border-sb-hairline pt-sb-3">
        <SummaryRow label="공통" tone="text-sb-pos-text" text={item.shared} />
        <SummaryRow label="차이" tone="text-sb-amber-text" text={item.different} />
      </div>
    </article>
  )
}
