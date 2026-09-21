import { useState } from "react"
import { Link } from "react-router"
import { cn } from "@/lib/cn"
import { EvidenceSteps } from "@/pages/Landing/components/EvidenceSteps"
import { HeroMarquee } from "@/pages/Landing/components/HeroMarquee"
import { OutcomeCaseCard } from "@/pages/Landing/components/OutcomeCaseCard"
import { Reveal } from "@/pages/Landing/components/Reveal"
import { TypingText } from "@/pages/Landing/components/TypingText"
import { UnknownTrendChart } from "@/pages/Landing/components/UnknownTrendChart"
import { OUTCOME_CASES, PLAN_DRAFT_SLOTS, PLAN_DRAFT_TEXT } from "@/pages/Landing/demoData"
import { paths } from "@/router/paths"
import { useSession } from "@/hooks/queries/sessionQueries"

const SECTION_CLASS =
  "mx-auto max-w-sb-page px-sb-4 py-sb-12 md:px-sb-landing-gutter md:py-sb-landing-section"

const PRIMARY_ACTION_CLASS =
  "group inline-flex cursor-pointer items-center gap-sb-3 rounded-sb-control bg-sb-primary px-sb-6 text-sb-body font-medium text-sb-on-primary transition duration-200 hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:scale-[0.98] active:bg-sb-primary-deep motion-reduce:transition-none"

/** 결과군 카드는 100ms 간격으로 순차 등장한다 (plan 3절 04). */
const CASE_DELAYS = [0, 100, 200] as const

/** 비로그인이면 CTA를 로그인 페이지로 보내고 문구를 바꾼다. 세션 확인 중에는 기본 문구를 유지한다. */
function useStartCta() {
  const session = useSession()
  const loggedOut = session.isSuccess && !session.data.authenticated
  return loggedOut
    ? { to: paths.login, label: "로그인 후 시작" }
    : { to: paths.games, label: "게임 탐색으로 이동" }
}

/**
 * Hero CTA. magicui shimmer-button 구조를 따라 테두리를 따라 도는 빛을 얹는다.
 * 빛은 뒤판(backdrop)이 가리고 남은 테두리 폭에서만 보인다.
 */
function HeroCtaLink() {
  const cta = useStartCta()
  return (
    <Link
      to={cta.to}
      className="group relative z-0 inline-flex h-13 transform-gpu cursor-pointer items-center justify-center gap-sb-3 overflow-hidden rounded-sb-control border border-sb-ink/10 bg-sb-primary px-sb-6 text-sb-body font-medium whitespace-nowrap text-sb-on-primary transition-transform duration-200 ease-in-out hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:translate-y-px active:bg-sb-primary-deep motion-reduce:transition-none"
    >
      <span
        aria-hidden
        className="absolute inset-0 -z-30 overflow-visible blur-[1px] [container-type:size]"
      >
        <span className="absolute inset-0 aspect-square h-[100cqh] animate-sb-shimmer-slide motion-reduce:animate-none">
          <span className="absolute -inset-full w-auto animate-sb-spin-around [background:conic-gradient(from_225deg,transparent_0,var(--color-sb-ink)_90deg,transparent_90deg)] motion-reduce:animate-none" />
        </span>
      </span>
      {cta.label}
      <span
        aria-hidden
        className="absolute inset-0 size-full rounded-sb-control shadow-[inset_0_-8px_10px_#ffffff1f] transition-shadow duration-300 ease-in-out group-hover:shadow-[inset_0_-6px_10px_#ffffff3f] group-active:shadow-[inset_0_-10px_10px_#ffffff3f] motion-reduce:transition-none"
      />
      <span
        aria-hidden
        className="absolute inset-[2px] -z-20 rounded-sb-control bg-sb-primary group-hover:bg-sb-primary-soft group-active:bg-sb-primary-deep"
      />
    </Link>
  )
}

function HeroSection() {
  return (
    <section className="relative isolate min-h-168 overflow-hidden bg-sb-canvas-night md:min-h-[calc(100svh-var(--spacing-sb-header))]">
      <HeroMarquee />

      <div className="relative flex min-h-168 flex-col items-center justify-center gap-sb-6 px-sb-4 py-sb-12 text-center md:min-h-[calc(100svh-var(--spacing-sb-header))] md:px-sb-landing-gutter md:py-sb-landing-section">
        <Reveal variant="eyebrow">
          <p className="font-sb-mono text-sb-caption tracking-[0.1em] text-sb-primary">
            STEAM REVIEW INTELLIGENCE
          </p>
        </Reveal>
        <Reveal variant="title" delay={80}>
          <h1 className="max-w-3xl text-sb-section font-medium text-balance text-sb-ink md:text-sb-display">
            유저의 반응을 다음 패치의 판단 근거로
          </h1>
        </Reveal>
        <Reveal variant="lead" delay={180}>
          <p className="max-w-xl text-sb-body text-pretty text-sb-ink-mute md:text-sb-lead">
            쌓이는 Steam 리뷰와 패치 이력. 유저의 반응을 읽고, 다음 변화의 근거를 찾으세요.
          </p>
        </Reveal>
        <Reveal variant="cta" delay={280}>
          <HeroCtaLink />
        </Reveal>
      </div>

      <span
        aria-hidden
        className="absolute inset-x-0 bottom-sb-6 hidden flex-col items-center gap-sb-2 md:flex"
      >
        <span className="font-sb-mono text-sb-caption tracking-[0.3em] text-sb-ink-mute-2">
          SCROLL
        </span>
        <svg viewBox="0 0 20 32" className="h-8 w-5 text-sb-ink-mute" fill="none">
          <rect x="1" y="1" width="18" height="30" rx="9" stroke="currentColor" strokeWidth="1.5" />
          <circle
            cx="10"
            cy="9"
            r="1.75"
            fill="currentColor"
            className="animate-sb-scroll-hint motion-reduce:animate-none"
          />
        </svg>
      </span>
    </section>
  )
}

function QuestionSection() {
  return (
    <section className="relative isolate overflow-hidden bg-sb-canvas-base">
      {/* 배경: 패치 이후가 가려진 반응 추세 그래프 */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 flex items-center justify-center px-sb-4"
      >
        <div className="w-full opacity-70 md:opacity-85">
          <UnknownTrendChart />
        </div>
      </div>
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-linear-to-b from-sb-canvas-base via-sb-canvas-base/70 to-sb-canvas-base"
      />

      <div
        className={cn(
          SECTION_CLASS,
          "relative flex min-h-140 flex-col items-center justify-center gap-sb-6 text-center md:min-h-176",
        )}
      >
        <p className="text-sb-lead text-sb-ink-mute">
          <TypingText text="패치는 끝났습니다. 그런데," speed={50} />
        </p>
        <h2 className="text-sb-section font-medium text-balance text-sb-ink">
          <TypingText text="유저의 마음도 달라졌을까요?" speed={70} delay={800} />
        </h2>
        <Reveal variant="lead" delay={360}>
          <ul className="flex flex-wrap justify-center gap-sb-6 text-sb-caption text-sb-ink-mute md:gap-sb-12">
            {["공개된 Steam 리뷰", "패치노트와 나란히", "요약에서 원문까지"].map((item) => (
              <li key={item} className="flex items-center gap-sb-2">
                <span aria-hidden className="h-1 w-1 rounded-full bg-sb-primary" />
                {item}
              </li>
            ))}
          </ul>
        </Reveal>
      </div>
    </section>
  )
}

function EvidenceSection() {
  return (
    <section className="bg-sb-canvas-base">
      <div className={SECTION_CLASS}>
        <EvidenceSteps
          heading={
            <>
              <Reveal variant="eyebrow">
                <p className="font-sb-mono text-sb-caption text-sb-primary">
                  01 — 이 게임의 반응을 읽다
                </p>
              </Reveal>
              <Reveal variant="title" delay={80} className="mt-sb-4">
                <h2 className="max-w-2xl text-sb-section font-medium text-balance text-sb-ink">
                  평점 아래에 있는 진짜 이야기, 요약에서 원문까지.
                </h2>
              </Reveal>
              <Reveal variant="lead" delay={140} className="mt-sb-3">
                <p className="max-w-2xl text-sb-body text-pretty text-sb-ink-mute">
                  선택한 게임의 패치 전후 리뷰를 모아, 반응이 어디서 어떻게 움직였는지 봅니다.
                </p>
              </Reveal>
            </>
          }
        />
      </div>
    </section>
  )
}

/**
 * 04 비교 섹션 — 기획안을 적고 유사 사례를 찾는 흐름.
 * 기획안 화면의 입력 패널을 그대로 옮긴 표시용 화면이며 실제 입력을 받지 않는다.
 */
function PlanSearchFlow({ typed, onTyped }: { typed: boolean; onTyped: () => void }) {
  return (
    <div className="flex flex-col items-center">
      <Reveal variant="panel" className="w-full">
        <div className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface">
          <div className="flex flex-wrap items-baseline gap-x-sb-2 gap-y-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
            <h3 className="text-sb-body font-medium text-sb-ink">다음 버전 기획안</h3>
            <p className="text-sb-caption text-sb-ink-mute">
              자연어로 변경안을 적으면 변경 대상의 의미부터 확인합니다
            </p>
          </div>
          <div className="flex flex-col gap-sb-3 p-sb-4">
            <p className="rounded-sb-control border border-sb-hairline-strong bg-sb-canvas-soft px-sb-3 py-sb-2 text-left text-sb-body text-sb-ink">
              <TypingText text={PLAN_DRAFT_TEXT} speed={25} onComplete={onTyped} />
            </p>
            <ul className="flex flex-wrap gap-sb-2">
              {PLAN_DRAFT_SLOTS.map((slot) => (
                <li
                  key={slot.label}
                  className="flex items-center gap-sb-2 rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas px-sb-2 py-0.5 text-sb-caption"
                >
                  <span className="text-sb-ink-mute">{slot.label}</span>
                  <span className="text-sb-ink">{slot.value}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </Reveal>

      <Reveal variant="cta" ready={typed} className="flex flex-col items-center">
        <span
          aria-hidden
          className="block h-8 w-px bg-linear-to-b from-sb-hairline-strong to-sb-primary"
        />
        <span className="mt-sb-2 inline-flex h-sb-control items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-body text-sb-ink">
          유사 사례 검색
          <span aria-hidden className="text-sb-primary">
            ↓
          </span>
        </span>
      </Reveal>
    </div>
  )
}

function ComparisonSection() {
  const [typed, setTyped] = useState(false)
  return (
    <section className="bg-sb-canvas-base">
      <div className={SECTION_CLASS}>
        <Reveal variant="eyebrow">
          <p className="font-sb-mono text-sb-caption text-sb-primary">02 — 다음 변화를 준비하다</p>
        </Reveal>
        <div className="mt-sb-4 flex flex-col gap-sb-6 lg:flex-row lg:items-end lg:justify-between">
          <Reveal variant="title">
            <h2 className="max-w-xl text-sb-section font-medium text-balance text-sb-ink">
              비슷한 패치, 다른 반응. 결정 전에 살펴보세요.
            </h2>
          </Reveal>
          <Reveal variant="lead" delay={140} className="max-w-md">
            <p className="text-sb-body text-pretty text-sb-ink-mute">
              기획안을 적으면 의미가 비슷한 과거 패치를 찾아줍니다. 공통점과 차이점을 살피며 판단의
              근거를 더하세요.
            </p>
            <p className="mt-sb-3 text-sb-caption text-sb-ink-mute">
              좋은 반응도, 나쁜 반응도 함께 비교합니다.
            </p>
          </Reveal>
        </div>

        <div className="mt-sb-8">
          <PlanSearchFlow typed={typed} onTyped={() => setTyped(true)} />
        </div>

        <Reveal variant="lead" ready={typed} delay={100} className="mt-sb-6">
          <p className="text-center font-sb-mono text-sb-caption text-sb-ink-mute">
            유사 사례 {OUTCOME_CASES.length}건 · 결과군별로 나눠 보여줍니다
          </p>
        </Reveal>

        <ul className="mt-sb-4 grid gap-sb-4 lg:grid-cols-3">
          {OUTCOME_CASES.map((item, index) => (
            <li key={item.gameName}>
              <Reveal variant="group" ready={typed} delay={CASE_DELAYS[index]} className="h-full">
                <OutcomeCaseCard item={item} />
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

function CtaSection() {
  const cta = useStartCta()
  return (
    <section className="bg-sb-canvas-night">
      <div className={SECTION_CLASS}>
        <Reveal variant="title">
          <h2 className="max-w-2xl text-sb-section font-medium text-balance text-sb-ink">
            다음 패치의 시작, 유저의 반응에서.
          </h2>
        </Reveal>
        <div className="flex flex-wrap mt-sb-6 items-center gap-sb-6">
          <Link to={cta.to} className={cn(PRIMARY_ACTION_CLASS, "h-sb-control")}>
            {cta.label}
          </Link>
          <p className="text-sb-body text-sb-ink-mute">공개된 Steam 데이터로 시작합니다.</p>
        </div>
      </div>
    </section>
  )
}

/**
 * 랜딩 페이지. 구성과 문구는 thispatch.pen `SB / 00 랜딩 페이지`,
 * 연출은 docs/landing-animation-plan.md를 따른다.
 * 랜딩에서는 분석 API를 호출하지 않고 준비된 표시 데이터를 쓴다.
 */
export default function LandingPage() {
  return (
    <>
      {/* 한국어 본문이 단어 중간에서 끊기지 않게 한다. */}
      <main className="break-keep">
        <HeroSection />
        <QuestionSection />
        <EvidenceSection />
        <ComparisonSection />
        <CtaSection />
      </main>
    </>
  )
}
