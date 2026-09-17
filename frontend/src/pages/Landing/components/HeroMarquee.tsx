import { useLayoutEffect, useRef, useState, type ReactNode } from "react"
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion"
import { cn } from "@/lib/cn"
import { formatPercent } from "@/lib/format"
import {
  MARQUEE_GAMES,
  MARQUEE_REVIEWS,
  type MarqueeGame,
  type MarqueeReview,
} from "@/pages/Landing/demoData"

/** 데스크톱 전환 폭. 트랙 속도를 고를 때만 쓴다. */
const DESKTOP_MIN_WIDTH = 768

/** 트랙 이동 속도(px/s). plan 3절 01 Hero. */
const TRACK_SPEED = {
  games: { mobile: 18, desktop: 28 },
  reviews: { mobile: 14, desktop: 20 },
}

const POSITIVE_RATE_MIN = 80
const NEUTRAL_RATE_MIN = 60

/** 게임 목록 카드와 같은 긍정률 구간 색. */
const RATE_TONE = {
  positive: {
    text: "text-sb-pos-text",
    bar: "[&::-webkit-progress-value]:bg-sb-pos [&::-moz-progress-bar]:bg-sb-pos",
  },
  neutral: {
    text: "text-sb-amber-text",
    bar: "[&::-webkit-progress-value]:bg-sb-mark [&::-moz-progress-bar]:bg-sb-mark",
  },
  negative: {
    text: "text-sb-neg-text",
    bar: "[&::-webkit-progress-value]:bg-sb-neg [&::-moz-progress-bar]:bg-sb-neg",
  },
}

function rateTone(rate: number) {
  if (rate >= POSITIVE_RATE_MIN) return RATE_TONE.positive
  if (rate >= NEUTRAL_RATE_MIN) return RATE_TONE.neutral
  return RATE_TONE.negative
}

function MarqueeGameCard({ game }: { game: MarqueeGame }) {
  const tone = rateTone(game.positiveRate)
  return (
    <article className="w-48 shrink-0 overflow-hidden rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface md:w-70">
      {/* 이미지를 불러오지 못하면 아래 그라데이션이 그대로 보인다. */}
      <div className="aspect-[460/215] w-full bg-linear-to-br from-sb-canvas-active to-sb-canvas-night">
        <img
          src={game.capsuleImageUrl}
          alt=""
          loading="lazy"
          className="h-full w-full object-cover"
        />
      </div>
      <div className="flex flex-col gap-sb-2 p-sb-3">
        <p className="truncate text-sb-title text-sb-ink">{game.name}</p>
        <ul className="flex gap-sb-1">
          {game.genres.map((genre) => (
            <li
              key={genre}
              className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-px text-sb-caption text-sb-ink-mute"
            >
              {genre}
            </li>
          ))}
        </ul>
        <div className="flex items-center justify-between">
          <span className="text-sb-caption text-sb-ink-mute-2">긍정률</span>
          <span className={cn("font-sb-mono text-sb-body tabular-nums", tone.text)}>
            {formatPercent(game.positiveRate)}
          </span>
        </div>
        <progress
          value={game.positiveRate}
          max={100}
          className={cn(
            "h-1.5 w-full appearance-none overflow-hidden rounded-full border-0 bg-sb-canvas-soft [&::-webkit-progress-bar]:bg-transparent",
            tone.bar,
          )}
        />
      </div>
    </article>
  )
}

function MarqueeReviewCard({ review }: { review: MarqueeReview }) {
  const isNegative = review.sentiment === "NEGATIVE"
  return (
    <article className="flex w-72 shrink-0 flex-col gap-sb-2 rounded-sb-control border border-sb-hairline bg-sb-canvas-surface p-sb-3 md:w-108">
      <div className="flex items-center gap-sb-2">
        <span
          className={cn(
            "rounded-sb-tag border px-sb-2 py-px text-sb-caption",
            isNegative
              ? "border-sb-line-red bg-sb-tint-red text-sb-neg-text"
              : "border-sb-line-green bg-sb-tint-green text-sb-pos-text",
          )}
        >
          {isNegative ? "부정" : "긍정"}
        </span>
        <span className="truncate font-sb-mono text-sb-caption text-sb-ink-mute">
          {review.meta}
        </span>
        <span className="ml-auto shrink-0 font-sb-mono text-sb-caption tabular-nums text-sb-ink-mute">
          ♥ {review.helpfulCount.toLocaleString("en-US")}
        </span>
      </div>
      <p className="line-clamp-2 text-sb-caption leading-relaxed text-sb-ink">{review.body}</p>
      <ul className="flex gap-sb-1">
        {review.tags.map((tag) => (
          <li
            key={tag}
            className="rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 py-px text-sb-caption text-sb-ink-mute"
          >
            {tag}
          </li>
        ))}
      </ul>
    </article>
  )
}

interface MarqueeTrackProps {
  direction: "ltr" | "rtl"
  speed: { mobile: number; desktop: number }
  paused: boolean
  className?: string
  children: ReactNode
}

/**
 * 같은 세트를 두 벌 이어 붙여 무한 루프한다.
 * 이동 거리는 세트 실측 너비(뒤쪽 간격 포함), 주기는 거리 ÷ 목표 속도다.
 */
function MarqueeTrack({ direction, speed, paused, className, children }: MarqueeTrackProps) {
  const trackRef = useRef<HTMLDivElement>(null)
  const setRef = useRef<HTMLDivElement>(null)
  const [hovered, setHovered] = useState(false)

  useLayoutEffect(() => {
    const track = trackRef.current
    const set = setRef.current
    if (!track || !set) return

    const sync = () => {
      const distance = set.getBoundingClientRect().width
      if (distance <= 0) return
      const pxPerSecond = window.innerWidth >= DESKTOP_MIN_WIDTH ? speed.desktop : speed.mobile
      track.style.setProperty("--sb-marquee-distance", `${distance}px`)
      track.style.setProperty("animation-duration", `${distance / pxPerSecond}s`)
    }

    sync()
    window.addEventListener("resize", sync)
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(sync)
    observer?.observe(set)
    return () => {
      window.removeEventListener("resize", sync)
      observer?.disconnect()
    }
  }, [speed])

  return (
    <div
      className={cn("overflow-hidden", className)}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div
        ref={trackRef}
        className={cn(
          "flex w-max will-change-transform motion-reduce:animate-none",
          direction === "ltr" ? "animate-sb-marquee-ltr" : "animate-sb-marquee-rtl",
          (paused || hovered) && "[animation-play-state:paused]",
        )}
      >
        <div ref={setRef} className="flex gap-sb-4 pr-sb-4">
          {children}
        </div>
        <div className="flex gap-sb-4 pr-sb-4">{children}</div>
      </div>
    </div>
  )
}

/**
 * Hero 배경 두 트랙. 장식 레이어이므로 보조 기술과 탭 순서에서 제외한다.
 * 화면 이탈·탭 숨김·수동 정지·hover·모션 축소 중 하나라도 해당하면 멈춘다.
 */
export function HeroMarquee() {
  const prefersReducedMotion = usePrefersReducedMotion()
  const rootRef = useRef<HTMLDivElement>(null)
  const [onScreen, setOnScreen] = useState(true)
  const [tabVisible, setTabVisible] = useState(true)

  useLayoutEffect(() => {
    const element = rootRef.current
    if (!element || typeof IntersectionObserver === "undefined") return
    const observer = new IntersectionObserver(([entry]) => setOnScreen(entry.isIntersecting))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  useLayoutEffect(() => {
    const sync = () => setTabVisible(document.visibilityState === "visible")
    sync()
    document.addEventListener("visibilitychange", sync)
    return () => document.removeEventListener("visibilitychange", sync)
  }, [])

  const paused = prefersReducedMotion || !onScreen || !tabVisible

  return (
    <div ref={rootRef} aria-hidden className="absolute inset-0 overflow-hidden select-none">
      {/* 아래 여백은 Hero 하단 안내줄이 트랙과 겹치지 않도록 비워 둔다. */}
      <div className="flex h-full flex-col justify-between pt-sb-6 pb-20 opacity-40 md:pb-24">
        <MarqueeTrack direction="ltr" speed={TRACK_SPEED.games} paused={paused}>
          {MARQUEE_GAMES.map((game) => (
            <MarqueeGameCard key={game.appId} game={game} />
          ))}
        </MarqueeTrack>
        <MarqueeTrack direction="rtl" speed={TRACK_SPEED.reviews} paused={paused}>
          {MARQUEE_REVIEWS.map((review) => (
            <MarqueeReviewCard key={review.id} review={review} />
          ))}
        </MarqueeTrack>
      </div>
      {/* 중앙 스크림과 좌우 페이드는 움직이지 않고 포인터도 가로채지 않는다. */}
      <div className="pointer-events-none absolute inset-0 bg-linear-to-b from-transparent via-sb-canvas-night/80 to-transparent" />
      <div className="pointer-events-none absolute inset-y-0 left-0 w-sb-8 bg-linear-to-r from-sb-canvas-night to-transparent md:w-sb-landing-gutter" />
      <div className="pointer-events-none absolute inset-y-0 right-0 w-sb-8 bg-linear-to-l from-sb-canvas-night to-transparent md:w-sb-landing-gutter" />
    </div>
  )
}
