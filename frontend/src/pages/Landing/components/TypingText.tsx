import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion"
import { cn } from "@/lib/cn"

interface TypingTextProps {
  text: string
  /** 글자 사이 간격(ms) */
  speed?: number
  /** 화면에 들어온 뒤 첫 글자까지의 지연(ms) */
  delay?: number
  className?: string
}

/**
 * 화면에 들어오면 글자를 하나씩 찍는다. magicui typing-animation과 같은 동작이며
 * 모션 라이브러리 없이 IntersectionObserver로 시작 시점을 잡는다.
 * 보조 기술에는 완성된 문장을 한 번에 전달하고, 모션 축소에서는 처음부터 전부 보여준다.
 */
export function TypingText({ text, speed = 55, delay = 0, className }: TypingTextProps) {
  const prefersReducedMotion = usePrefersReducedMotion()
  const graphemes = useMemo(() => Array.from(text), [text])
  const ref = useRef<HTMLSpanElement>(null)
  const [started, setStarted] = useState(false)
  const [typedCount, setTypedCount] = useState(0)
  const animates = !prefersReducedMotion && typeof IntersectionObserver !== "undefined"

  useLayoutEffect(() => {
    const element = ref.current
    if (!animates || !element) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return
        setStarted(true)
        observer.disconnect()
      },
      { threshold: 0.3 },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [animates])

  useEffect(() => {
    if (!animates || !started || typedCount >= graphemes.length) return
    const timer = setTimeout(
      () => setTypedCount((count) => count + 1),
      typedCount === 0 ? delay : speed,
    )
    return () => clearTimeout(timer)
  }, [animates, started, typedCount, graphemes.length, delay, speed])

  // 커서는 첫 글자가 찍힌 뒤부터 다 찍기 전까지만 보여 준다.
  const showCaret = animates && typedCount > 0 && typedCount < graphemes.length

  return (
    <span ref={ref} className={cn("grid", className)}>
      <span className="sr-only">{text}</span>
      {/* 다 찍었을 때의 크기를 미리 잡아 줄바꿈이 튀지 않게 한다. */}
      <span aria-hidden className="invisible col-start-1 row-start-1">
        {text}
      </span>
      <span aria-hidden className="col-start-1 row-start-1">
        {animates ? graphemes.slice(0, typedCount).join("") : text}
        {showCaret ? (
          <span className="animate-sb-caret-blink text-sb-primary motion-reduce:animate-none">
            |
          </span>
        ) : null}
      </span>
    </span>
  )
}
