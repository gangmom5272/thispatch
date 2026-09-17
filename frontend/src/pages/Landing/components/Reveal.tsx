import { useLayoutEffect, useRef, useState, type ReactNode } from "react"
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion"
import { cn } from "@/lib/cn"

/** 섹션 시작이 뷰포트 높이의 약 80% 선에 닿으면 재생한다 (plan 2절). */
const ROOT_MARGIN = "0px 0px -20% 0px"

/**
 * 등장 종류별 지속 시간과 시작 위치. plan 2·3절 시작값이며 모바일은 짧고 가깝게 움직인다.
 * 시작 위치는 숨은 동안에만 적용해 최종 상태에서는 변환이 남지 않는다.
 */
const VARIANT = {
  /** 상단 작은 문구 — 이동 없이 페이드만 */
  eyebrow: { duration: "duration-[300ms] md:duration-[400ms]", from: "" },
  /** 제목 — 아래 16px */
  title: {
    duration: "duration-[400ms] md:duration-[600ms]",
    from: "translate-y-2 md:translate-y-4",
  },
  /** 설명·본문 — 아래 12px */
  lead: {
    duration: "duration-[400ms] md:duration-[500ms]",
    from: "translate-y-2 md:translate-y-3",
  },
  /** CTA·버튼 — 아래 8px */
  cta: { duration: "duration-[350ms] md:duration-[450ms]", from: "translate-y-2" },
  /** 실제 UI 패널 — 아래 20px */
  panel: {
    duration: "duration-[400ms] md:duration-[600ms]",
    from: "translate-y-2 md:translate-y-5",
  },
  /** 카드·묶음 — 아래 16px */
  group: {
    duration: "duration-[400ms] md:duration-[550ms]",
    from: "translate-y-2 md:translate-y-4",
  },
  /** 단계 구분선 — 시작점 기준 가로 확장 (호출부에서 origin-left 지정) */
  line: { duration: "duration-[400ms] md:duration-[500ms]", from: "scale-x-0" },
} as const

/** 요소 간 지연. 키는 데스크톱 값(ms), 모바일은 절반 수준으로 줄인다 (plan 2절). */
const DELAY_CLASS = {
  0: "",
  80: "delay-[40ms] md:delay-[80ms]",
  100: "delay-[60ms] md:delay-[100ms]",
  120: "delay-[60ms] md:delay-[120ms]",
  140: "delay-[80ms] md:delay-[140ms]",
  160: "delay-[80ms] md:delay-[160ms]",
  180: "delay-[80ms] md:delay-[180ms]",
  200: "delay-[100ms] md:delay-[200ms]",
  240: "delay-[120ms] md:delay-[240ms]",
  280: "delay-[140ms] md:delay-[280ms]",
  300: "delay-[150ms] md:delay-[300ms]",
  360: "delay-[180ms] md:delay-[360ms]",
} as const

interface RevealProps {
  children: ReactNode
  variant?: keyof typeof VARIANT
  delay?: keyof typeof DELAY_CLASS
  className?: string
  id?: string
}

/**
 * 뷰포트 진입 시 1회 등장. 한 번 나타난 내용은 역스크롤해도 유지한다.
 * 모션 축소이거나 IntersectionObserver를 쓸 수 없으면 처음부터 최종 상태로 보여준다.
 */
export function Reveal({ children, variant = "title", delay = 0, className, id }: RevealProps) {
  const prefersReducedMotion = usePrefersReducedMotion()
  const [shown, setShown] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  // 모션 축소이거나 관찰자를 쓸 수 없으면 등장 연출 없이 최종 상태로 둔다.
  const animates = !prefersReducedMotion && typeof IntersectionObserver !== "undefined"

  useLayoutEffect(() => {
    const element = ref.current
    if (!animates || !element) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return
        setShown(true)
        observer.disconnect()
      },
      { rootMargin: ROOT_MARGIN },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [animates])

  const hidden = animates && !shown

  return (
    <div
      ref={ref}
      id={id}
      className={cn(
        "ease-sb-enter transition-[opacity,transform] motion-reduce:transition-none",
        VARIANT[variant].duration,
        DELAY_CLASS[delay],
        hidden ? cn("opacity-0", VARIANT[variant].from) : "opacity-100",
        className,
      )}
    >
      {children}
    </div>
  )
}
