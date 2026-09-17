import { useCallback, useRef, useState, type PointerEvent as ReactPointerEvent } from "react"

const DEFAULT_THRESHOLD_PX = 6

interface UseDragScrollOptions {
  /** 드래그가 클릭과 구분되는 최소 이동 거리(px) */
  thresholdPx?: number
  /** 임계값을 넘는 순간(차트 scroll unlock 등) */
  onDragStart?: () => void
}

/**
 * overflow-x 컨테이너를 마우스/포인터 드래그로 가로 스크롤한다.
 * 임계값 미만 이동은 자식 링크·버튼 클릭을 유지한다.
 */
export function useDragScroll(options: UseDragScrollOptions = {}) {
  const thresholdPx = options.thresholdPx ?? DEFAULT_THRESHOLD_PX
  const onDragStart = options.onDragStart

  const elRef = useRef<HTMLElement | null>(null)
  const pointerIdRef = useRef<number | null>(null)
  // active: pointerdown 이후 아직 up 전
  const activeRef = useRef(false)
  // dragged: 임계값을 넘어 실제 스크롤로 취급 중
  const draggedRef = useRef(false)
  const startXRef = useRef(0)
  const startScrollRef = useRef(0)
  const [isDragging, setIsDragging] = useState(false)

  const setRef = useCallback((node: HTMLElement | null) => {
    elRef.current = node
  }, [])

  const prefersReducedMotion = () =>
    typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches

  const endDrag = useCallback((event?: ReactPointerEvent<HTMLElement>) => {
    const el = elRef.current
    if (el && event && pointerIdRef.current !== null) {
      try {
        el.releasePointerCapture(pointerIdRef.current)
      } catch {
        // capture가 이미 해제된 경우 무시
      }
    }
    pointerIdRef.current = null
    activeRef.current = false
    setIsDragging(false)
  }, [])

  const onPointerDown = useCallback((event: ReactPointerEvent<HTMLElement>) => {
    if (event.button !== 0 || prefersReducedMotion()) return
    const el = elRef.current
    if (!el) return

    activeRef.current = true
    draggedRef.current = false
    startXRef.current = event.clientX
    startScrollRef.current = el.scrollLeft
    pointerIdRef.current = event.pointerId
    el.setPointerCapture(event.pointerId)
  }, [])

  const onPointerMove = useCallback(
    (event: ReactPointerEvent<HTMLElement>) => {
      if (!activeRef.current || pointerIdRef.current !== event.pointerId) return
      const el = elRef.current
      if (!el) return

      // deltaX: 포인터 이동량(px). 양수면 오른쪽으로 드래그
      const deltaX = event.clientX - startXRef.current
      if (!draggedRef.current) {
        if (Math.abs(deltaX) < thresholdPx) return
        draggedRef.current = true
        setIsDragging(true)
        onDragStart?.()
      }

      event.preventDefault()
      el.scrollLeft = startScrollRef.current - deltaX
    },
    [onDragStart, thresholdPx],
  )

  const onPointerUp = useCallback(
    (event: ReactPointerEvent<HTMLElement>) => {
      if (pointerIdRef.current !== event.pointerId) return
      endDrag(event)
    },
    [endDrag],
  )

  /** 드래그 직후 자식 클릭이 발화되지 않도록 캡처 단계에서 차단한다. */
  const onClickCapture = useCallback((event: React.MouseEvent<HTMLElement>) => {
    if (!draggedRef.current) return
    event.preventDefault()
    event.stopPropagation()
    draggedRef.current = false
  }, [])

  return {
    setRef,
    isDragging,
    dragProps: {
      onPointerDown,
      onPointerMove,
      onPointerUp,
      onPointerCancel: onPointerUp,
      onClickCapture,
      className: isDragging ? "cursor-grabbing select-none" : "cursor-grab",
      style: { touchAction: "pan-y" as const },
    },
  }
}
