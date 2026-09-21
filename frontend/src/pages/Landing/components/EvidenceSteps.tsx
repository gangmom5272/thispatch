import { useEffect, useId, useRef, useState, type ReactNode } from "react"
import RateBar from "@/components/RateBar"
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion"
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

/** 뷰포트 한가운데를 지나는 스크롤 구간이 현재 단계가 된다. */
const SLOT_ROOT_MARGIN = "-50% 0px -50% 0px"

/** 단계가 바뀔 때 이전 화면이 사라지고 다음 화면이 나타나는 데 쓰는 시간. */
const PANEL_FADE_MS = 180

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
      <TopicBars
        topics={DIAGNOSIS_TOPICS}
        reviewCount={DIAGNOSIS_REVIEW_COUNT}
        isOverall={false}
        dense
      />
    )
  }
  if (index === 2) {
    return <TopicAiSummaryCard data={DIAGNOSIS_AI_SUMMARY} isPending={false} isError={false} />
  }
  return <RepresentativeReviewsPanel />
}

/**
 * 근거의 네 단계 선택기.
 * 데스크톱에서는 화면이 고정된 채 스크롤이 한 구간 내려갈 때마다 다음 단계 화면으로 넘어가고,
 * 스크롤 구간을 두지 않는 좁은 화면에서는 단계를 눌러 바꾼다.
 */
export function EvidenceSteps({ heading }: { heading: ReactNode }) {
  const panelId = useId()
  const prefersReducedMotion = usePrefersReducedMotion()
  const [activeStep, setActiveStep] = useState(0)
  const [shownStep, setShownStep] = useState(0)
  const slotRefs = useRef<Array<HTMLDivElement | null>>([])

  // 활성 단계가 먼저 바뀌고 이전 화면이 사라진 뒤 표시 단계가 따라가면서 페이드아웃·페이드인이 이어진다.
  useEffect(() => {
    if (shownStep === activeStep) return
    const timer = setTimeout(
      () => setShownStep(activeStep),
      prefersReducedMotion ? 0 : PANEL_FADE_MS,
    )
    return () => clearTimeout(timer)
  }, [activeStep, shownStep, prefersReducedMotion])

  useEffect(() => {
    const slots = slotRefs.current.filter((slot) => slot !== null)
    if (slots.length === 0 || typeof IntersectionObserver === "undefined") return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) setActiveStep(Number(entry.target.getAttribute("data-step")))
        }
      },
      { rootMargin: SLOT_ROOT_MARGIN },
    )
    slots.forEach((slot) => observer.observe(slot))
    return () => observer.disconnect()
  }, [])

  return (
    /* 트랙 높이는 단계 수 × 한 단계에 쓰는 스크롤 거리(100vh)다. */
    <div className="relative md:h-[400vh]">
      <div aria-hidden className="pointer-events-none absolute inset-0 hidden flex-col md:flex">
        {EVIDENCE_STEPS.map((item, index) => (
          <div
            key={item.step}
            data-step={index}
            ref={(node) => {
              slotRefs.current[index] = node
            }}
            className="flex-1"
          />
        ))}
      </div>

      <div className="md:sticky md:top-sb-12">
        {heading}
        <ol className="mt-sb-8 grid gap-sb-4 md:grid-cols-4">
          {EVIDENCE_STEPS.map((item, index) => {
            const isActive = index === activeStep
            const select = () => {
              setActiveStep(index)
              slotRefs.current[index]?.scrollIntoView({
                block: "center",
                behavior: prefersReducedMotion ? "auto" : "smooth",
              })
            }
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
                  <span className="mt-sb-1 block text-sb-caption text-sb-ink-mute">
                    {item.hint}
                  </span>
                </button>
              </li>
            )
          })}
        </ol>

        <Reveal variant="group" id={panelId} className="mt-sb-8">
          <div
            className={cn(
              "ease-sb-enter transition-opacity duration-[180ms] motion-reduce:transition-none",
              shownStep === activeStep || prefersReducedMotion ? "opacity-100" : "opacity-0",
            )}
          >
            <StepPanel index={shownStep} />
          </div>
        </Reveal>
      </div>
    </div>
  )
}
