import { Link } from "react-router"
import { cn } from "@/lib/cn"
import { TopicAiSummaryCard } from "@/pages/GameDetail/PlaytimeTopics/components/TopicAiSummaryCard"
import { TopicBars } from "@/pages/GameDetail/PlaytimeTopics/components/TopicBars"
import { EvidenceSteps } from "@/pages/Landing/components/EvidenceSteps"
import { HeroMarquee } from "@/pages/Landing/components/HeroMarquee"
import { OutcomeCaseCard } from "@/pages/Landing/components/OutcomeCaseCard"
import { Reveal } from "@/pages/Landing/components/Reveal"
import { TypingText } from "@/pages/Landing/components/TypingText"
import { UnknownTrendChart } from "@/pages/Landing/components/UnknownTrendChart"
import {
  DIAGNOSIS_AI_SUMMARY,
  DIAGNOSIS_REVIEW_COUNT,
  DIAGNOSIS_TOPICS,
  OUTCOME_CASES,
} from "@/pages/Landing/demoData"
import { paths } from "@/router/paths"

const SECTION_CLASS =
  "mx-auto max-w-sb-page px-sb-4 py-sb-12 md:px-sb-landing-gutter md:py-sb-landing-section"

const PRIMARY_ACTION_CLASS =
  "group inline-flex cursor-pointer items-center gap-sb-3 rounded-sb-control bg-sb-primary px-sb-6 text-sb-body font-medium text-sb-on-primary transition duration-200 hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:scale-[0.98] active:bg-sb-primary-deep motion-reduce:transition-none"

const FOOTER_LINKS = ["데이터 출처", "한계와 범위", "시연 데이터 안내"]

/** 결과군 카드는 100ms 간격으로 순차 등장한다 (plan 3절 04). */
const CASE_DELAYS = [0, 100, 200] as const

function ActionArrow() {
  return (
    <span
      aria-hidden
      className="transition-transform duration-200 group-hover:translate-x-[3px] motion-reduce:transition-none"
    >
      ↗
    </span>
  )
}

/**
 * Hero CTA. magicui shimmer-button 구조를 따라 테두리를 따라 도는 빛을 얹는다.
 * 빛은 뒤판(backdrop)이 가리고 남은 테두리 폭에서만 보인다.
 */
function HeroCtaLink() {
  return (
    <Link
      to={paths.home}
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
      게임 목록에서 시작
      <ActionArrow />
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

      <div className="relative flex justify-center px-sb-4 pb-sb-6 md:px-sb-landing-gutter">
        <span aria-hidden className="hidden flex-col items-center gap-sb-2 md:flex">
          <span className="font-sb-mono text-sb-caption tracking-[0.3em] text-sb-ink-mute-2">
            SCROLL
          </span>
          <span className="block h-8 w-px animate-sb-scroll-hint bg-linear-to-b from-transparent to-sb-ink-mute motion-reduce:animate-none" />
        </span>
      </div>
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
        <div className="w-full opacity-60 md:opacity-75">
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
          "relative flex min-h-140 flex-col items-center justify-center gap-sb-6 text-center",
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

function DiagnosisSection() {
  return (
    <section className="bg-sb-canvas-base">
      <div className={SECTION_CLASS}>
        <Reveal variant="eyebrow">
          <p className="font-sb-mono text-sb-caption text-sb-primary">01 — 반응을 읽다</p>
        </Reveal>
        <div className="mt-sb-4 flex flex-col gap-sb-6 lg:flex-row lg:items-end lg:justify-between">
          <Reveal variant="title">
            <h2 className="max-w-xl text-sb-section font-medium text-balance text-sb-ink">
              평점 아래에 있는 진짜 이야기를 읽으세요.
            </h2>
          </Reveal>
          <Reveal variant="lead" delay={140}>
            <p className="max-w-md text-sb-body text-pretty text-sb-ink-mute">
              누가 어떤 이야기를 하는지. 플레이타임과 토픽으로 좁혀보세요.
            </p>
          </Reveal>
        </div>

        <div className="mt-sb-8 grid gap-sb-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <Reveal variant="panel">
            <TopicBars
              topics={DIAGNOSIS_TOPICS}
              reviewCount={DIAGNOSIS_REVIEW_COUNT}
              isOverall={false}
            />
          </Reveal>
          <Reveal variant="panel" delay={140}>
            <TopicAiSummaryCard data={DIAGNOSIS_AI_SUMMARY} isPending={false} isError={false} />
          </Reveal>
        </div>
      </div>
    </section>
  )
}

function ComparisonSection() {
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

        <ul className="mt-sb-8 grid gap-sb-4 lg:grid-cols-3">
          {OUTCOME_CASES.map((item, index) => (
            <li key={item.gameName}>
              <Reveal variant="group" delay={CASE_DELAYS[index]} className="h-full">
                <OutcomeCaseCard item={item} />
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

function EvidenceSection() {
  return (
    <section className="bg-sb-canvas-base">
      <div className={SECTION_CLASS}>
        <Reveal variant="eyebrow">
          <p className="font-sb-mono text-sb-caption text-sb-primary">03 — 근거까지 확인하다</p>
        </Reveal>
        <Reveal variant="title" delay={80} className="mt-sb-4">
          <h2 className="max-w-2xl text-sb-section font-medium text-balance text-sb-ink">
            요약은 시작일 뿐. 마지막에는, 유저의 목소리.
          </h2>
        </Reveal>

        <div className="mt-sb-8">
          <EvidenceSteps />
        </div>
      </div>
    </section>
  )
}

function CtaSection() {
  return (
    <section className="bg-sb-canvas-night">
      <div className={SECTION_CLASS}>
        <Reveal variant="title">
          <h2 className="max-w-2xl text-sb-section font-medium text-balance text-sb-ink">
            다음 패치의 시작, 유저의 반응에서.
          </h2>
        </Reveal>
        <Reveal variant="cta" delay={100} className="mt-sb-6">
          <div className="flex flex-wrap items-center gap-sb-6">
            <Link to={paths.home} className={cn(PRIMARY_ACTION_CLASS, "h-sb-control")}>
              게임 목록 열기
              <ActionArrow />
            </Link>
            <p className="text-sb-body text-sb-ink-mute">공개된 Steam 데이터로 시작합니다.</p>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

function LandingFooter() {
  return (
    <footer className="border-t border-sb-hairline bg-sb-canvas-base break-keep">
      <div className="mx-auto max-w-sb-page px-sb-4 py-sb-8 md:px-sb-landing-gutter">
        <div className="flex flex-wrap items-center justify-between gap-sb-4 text-sb-body text-sb-ink-mute">
          <span className="font-medium">ThisPatch</span>
          <ul className="flex flex-wrap gap-sb-6">
            <li>
              <Link
                to={paths.methodology}
                className="cursor-pointer rounded-sb-control hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                방법론
              </Link>
            </li>
            {FOOTER_LINKS.map((label) => (
              <li key={label}>{label}</li>
            ))}
          </ul>
        </div>
        <hr className="my-sb-5 border-sb-hairline" />
        <p className="text-sb-body leading-relaxed text-sb-ink-mute">
          Steam 및 관련 상표는 Valve Corporation의 자산이며 ThisPatch는 Valve와 제휴 관계가
          없습니다. 분석에는 공개된 Steam 리뷰·패치노트·상점 정보만 사용합니다. 화면의 수치는 관측된
          값이며 향후 리뷰 수정에 따라 과거 집계가 달라질 수 있습니다.
        </p>
      </div>
    </footer>
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
        <DiagnosisSection />
        <ComparisonSection />
        <EvidenceSection />
        <CtaSection />
      </main>
      <LandingFooter />
    </>
  )
}
