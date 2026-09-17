import { useId, useState } from "react"
import RateBar from "@/components/RateBar"
import { cn } from "@/lib/cn"
import { formatDeltaPp, formatPercent } from "@/lib/format"
import { TopicAiSummaryCard } from "@/pages/GameDetail/PlaytimeTopics/components/TopicAiSummaryCard"
import { TopicBars } from "@/pages/GameDetail/PlaytimeTopics/components/TopicBars"
import { RepresentativeReviewCard } from "@/pages/GameDetail/Reviews/components/RepresentativeReviewCard"
import { Reveal } from "@/pages/Landing/components/Reveal"
import {
  DIAGNOSIS_AI_SUMMARY,
  DIAGNOSIS_REVIEW_COUNT,
  DIAGNOSIS_TOPICS,
  EVIDENCE_RATE,
  EVIDENCE_REVIEWS,
  EVIDENCE_STEPS,
} from "@/pages/Landing/demoData"

/** 단계 구분선은 120ms 간격으로 순차 강조한다 (plan 3절 05). */
const STEP_DELAYS = [0, 120, 240, 360] as const

/** 처음에는 마지막 단계인 리뷰 원문을 보여준다. */
const DEFAULT_STEP = EVIDENCE_STEPS.length - 1

function Metric({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div>
      <p className="text-sb-caption text-sb-ink-mute">{label}</p>
      <p className={cn("font-sb-mono text-sb-title tabular-nums text-sb-ink", tone)}>{value}</p>
    </div>
  )
}

function PositiveRatePanel() {
  const delta = EVIDENCE_RATE.after - EVIDENCE_RATE.before

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-4">
        <h3 className="text-sb-title font-medium text-sb-ink">패치 전후 긍정률</h3>
        <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
          {EVIDENCE_RATE.periodLabel}
        </p>
      </header>

      <div className="flex flex-wrap gap-sb-8">
        <Metric label="패치 전" value={formatPercent(EVIDENCE_RATE.before)} />
        <Metric
          label="패치 후"
          value={formatPercent(EVIDENCE_RATE.after)}
          tone="text-sb-neg-text"
        />
        <Metric label="변화" value={formatDeltaPp(delta)} tone="text-sb-neg-text" />
        <Metric label="리뷰" value={`${EVIDENCE_RATE.reviewCount.toLocaleString("en-US")}건`} />
      </div>

      <div className="mt-sb-4">
        <RateBar before={EVIDENCE_RATE.before} after={EVIDENCE_RATE.after} className="h-2.5" />
      </div>
      <ul className="mt-sb-3 flex flex-wrap gap-sb-4 text-sb-caption text-sb-ink-mute">
        <li className="flex items-center gap-sb-2">
          <span
            aria-hidden
            className="inline-block h-2.5 w-6 rounded-sb-tag bg-sb-hairline-strong"
          />
          유지된 긍정 {formatPercent(EVIDENCE_RATE.after)}
        </li>
        <li className="flex items-center gap-sb-2">
          <span aria-hidden className="inline-block h-2.5 w-6 rounded-sb-tag bg-sb-neg" />
          하락분 {formatDeltaPp(delta)}
        </li>
        <li>
          긍정 {EVIDENCE_RATE.positiveCount}건 · 부정 {EVIDENCE_RATE.negativeCount}건
        </li>
      </ul>
    </section>
  )
}

function RepresentativeReviewsPanel() {
  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      <header className="mb-sb-4">
        <h3 className="text-sb-title font-medium text-sb-ink">최근 대표 리뷰</h3>
        <p className="mt-sb-1 text-sb-caption text-sb-ink-mute">
          요약의 근거가 된 리뷰를 원문 그대로 보여줍니다.
        </p>
      </header>
      <ul className="grid gap-sb-4 lg:grid-cols-2">
        {EVIDENCE_REVIEWS.map((review) => (
          <li key={review.id}>
            <RepresentativeReviewCard review={review} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function StepPanel({ index }: { index: number }) {
  if (index === 0) return <PositiveRatePanel />
  if (index === 1) {
    return (
      <TopicBars topics={DIAGNOSIS_TOPICS} reviewCount={DIAGNOSIS_REVIEW_COUNT} isOverall={false} />
    )
  }
  if (index === 2) {
    return <TopicAiSummaryCard data={DIAGNOSIS_AI_SUMMARY} isPending={false} isError={false} />
  }
  return <RepresentativeReviewsPanel />
}

/**
 * 근거의 네 단계 선택기.
 * 단계에 마우스를 올리거나 포커스하면 그 단계가 강조되고 아래 패널이 해당 화면으로 바뀐다.
 */
export function EvidenceSteps() {
  const panelId = useId()
  const [activeStep, setActiveStep] = useState(DEFAULT_STEP)

  return (
    <>
      <ol className="grid gap-sb-4 md:grid-cols-4">
        {EVIDENCE_STEPS.map((item, index) => {
          const isActive = index === activeStep
          const select = () => setActiveStep(index)
          return (
            <li key={item.step}>
              <Reveal variant="line" delay={STEP_DELAYS[index]} className="origin-left">
                <span
                  className={cn(
                    "block h-0.5 transition-colors duration-200 motion-reduce:transition-none",
                    isActive ? "bg-sb-primary" : "bg-sb-hairline-strong",
                  )}
                />
              </Reveal>
              <button
                type="button"
                aria-controls={panelId}
                onMouseEnter={select}
                onFocus={select}
                onClick={select}
                className="mt-sb-3 w-full cursor-pointer text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                <span className="block font-sb-mono text-sb-caption text-sb-ink-mute">
                  {item.step}
                </span>
                <span
                  className={cn(
                    "block text-sb-title transition-colors duration-200 motion-reduce:transition-none",
                    isActive ? "text-sb-primary" : "text-sb-ink",
                  )}
                >
                  {item.name}
                </span>
                <span className="mt-sb-1 block text-sb-caption text-sb-ink-mute">{item.hint}</span>
              </button>
            </li>
          )
        })}
      </ol>

      <Reveal variant="group" id={panelId} className="mt-sb-8 md:min-h-112">
        <StepPanel index={activeStep} />
      </Reveal>
    </>
  )
}
