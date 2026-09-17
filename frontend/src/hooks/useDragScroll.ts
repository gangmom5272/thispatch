import { useCallback, useEffect, useRef, useState } from "react"

const DEFAULT_THRESHOLD_PX = 8

interface UseDragScrollOptions {
  /** 드래그가 클릭과 구분되는 최소 이동 거리(px) */
  thresholdPx?: number
  /** 임계값을 넘는 순간(차트 scroll unlock 등) */
  onDragStart?: () => void
}

/**
 * overflow-x 컨테이너를 마우스 드래그로 가로 스크롤한다.
 * 링크·버튼 클릭은 유지하고, 임계값 이상 이동 시에만 스크롤로 전환한다.
 */
export function useDragScroll(options: UseDragScrollOptions = {}) {
  const thresholdPx = options.thresholdPx ?? DEFAULT_THRESHOLD_PX
  const onDragStart = options.onDragStart

  const elRef = useRef<HTMLElement | null>(null)
  // pointerId: 추적 중인 포인터. null이면 비활성
  const pointerIdRef = useRef<number | null>(null)
  const startXRef = useRef(0)
  const startScrollRef = useRef(0)
  // dragging: 임계값을 넘어 스크롤 중
  const draggingRef = useRef(false)
  // suppressClick: 드래그 직후 클릭 1회 차단
  const suppressClickRef = useRef(false)
  const [isDragging, setIsDragging] = useState(false)

  const setRef = useCallback((node: HTMLElement | null) => {
    elRef.current = node
  }, [])

  const prefersReducedMotion = () =>
    typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches

  const stopTracking = useCallback(() => {
    pointerIdRef.current = null
    draggingRef.current = false
    setIsDragging(false)
  }, [])

  useEffect(() => {
    const onPointerMove = (event: PointerEvent) => {
      if (pointerIdRef.current !== event.pointerId) return
      const el = elRef.current
      if (!el) return

      const deltaX = event.clientX - startXRef.current
      if (!draggingRef.current) {
        if (Math.abs(deltaX) < thresholdPx) return
        draggingRef.current = true
        suppressClickRef.current = true
        setIsDragging(true)
        onDragStart?.()
      }

      event.preventDefault()
      el.scrollLeft = startScrollRef.current - deltaX
    }

    const onPointerUp = (event: PointerEvent) => {
      if (pointerIdRef.current !== event.pointerId) return
      stopTracking()
    }

    window.addEventListener("pointermove", onPointerMove, { passive: false })
    window.addEventListener("pointerup", onPointerUp)
    window.addEventListener("pointercancel", onPointerUp)
    return () => {
      window.removeEventListener("pointermove", onPointerMove)
      window.removeEventListener("pointerup", onPointerUp)
      window.removeEventListener("pointercancel", onPointerUp)
    }
  }, [onDragStart, stopTracking, thresholdPx])

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0 || prefersReducedMotion()) return
    const el = elRef.current
    if (!el) return

    // 별 버튼 등은 드래그 스크롤을 시작하지 않는다.
    const target = event.target
    if (target instanceof Element && target.closest("button, input, textarea, select")) {
      return
    }

    pointerIdRef.current = event.pointerId
    draggingRef.current = false
    suppressClickRef.current = false
    startXRef.current = event.clientX
    startScrollRef.current = el.scrollLeft
  }, [])

  /** 드래그로 스크롤한 뒤 발생하는 클릭만 캡처 단계에서 막는다. */
  const onClickCapture = useCallback((event: React.MouseEvent<HTMLElement>) => {
    if (!suppressClickRef.current) return
    event.preventDefault()
    event.stopPropagation()
    suppressClickRef.current = false
  }, [])

  /** 카드 Link의 네이티브 드래그(이미지/URL)를 막아 가로 스크롤이 동작하게 한다. */
  const onDragStartCapture = useCallback((event: React.DragEvent<HTMLElement>) => {
    event.preventDefault()
  }, [])

  return {
    setRef,
    isDragging,
    dragProps: {
      onPointerDown,
      onClickCapture,
      onDragStartCapture,
      className: isDragging ? "cursor-grabbing select-none" : "cursor-grab",
      style: { touchAction: "pan-y" as const },
    },
  }
}
