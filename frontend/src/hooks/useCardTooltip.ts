import { useEffect, useRef, useState, type FocusEvent } from "react"

const TOOLTIP_WIDTH = 384
/** 카드와 툴팁 사이 간격(ml/mr-sb-2) */
const TOOLTIP_GAP = 8
const VIEWPORT_MARGIN = 16
/** 툴팁은 열리기 전엔 높이를 알 수 없어 아래로 넘칠지 판단할 때 쓰는 추정 높이 */
const TOOLTIP_ESTIMATED_HEIGHT = 360
/** 카드를 스치기만 할 때 툴팁이 깜빡이지 않도록 이만큼 머물러야 연다. */
const OPEN_DELAY_MS = 250

interface TooltipAnchor {
  side: "left" | "right"
  edge: "top" | "bottom"
}

/**
 * Steam 스토어처럼 카드 오른쪽 위 모서리에 맞춰 붙이되, 화면 오른쪽을 넘으면 왼쪽에 붙이고
 * 화면 아래를 넘을 것 같으면 아래 모서리에 맞춰 위로 자란다.
 */
function tooltipAnchorFor(card: DOMRect): TooltipAnchor {
  const fitsRight = card.right + TOOLTIP_GAP + TOOLTIP_WIDTH <= window.innerWidth - VIEWPORT_MARGIN
  const fitsBelow = card.top + TOOLTIP_ESTIMATED_HEIGHT <= window.innerHeight - VIEWPORT_MARGIN
  return { side: fitsRight ? "right" : "left", edge: fitsBelow ? "top" : "bottom" }
}

const anchorClass: Record<TooltipAnchor["side"], string> & Record<TooltipAnchor["edge"], string> = {
  right: "left-full ml-sb-2 starting:-translate-x-1",
  left: "right-full mr-sb-2 starting:translate-x-1",
  top: "top-0",
  bottom: "bottom-0",
}

/**
 * 카드 옆 요약 툴팁. 0.25초 이상 마우스를 올리거나 포커스가 들어오면 열린다.
 * 루트 요소에 rootRef와 handlers를 붙이고, 열려 있을 때 tooltipClassName으로 감싼 툴팁을 렌더링한다.
 * 툴팁은 포인터를 받지 않으므로 툴팁 위로 마우스가 지나가면 카드를 벗어난 것으로 처리된다.
 */
export function useCardTooltip<T extends HTMLElement>() {
  const [anchor, setAnchor] = useState<TooltipAnchor | null>(null)
  const rootRef = useRef<T>(null)
  const openTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined)

  useEffect(() => () => clearTimeout(openTimerRef.current), [])

  const open = () => {
    const root = rootRef.current
    if (!root) return
    setAnchor(tooltipAnchorFor(root.getBoundingClientRect()))
  }
  const close = () => {
    clearTimeout(openTimerRef.current)
    setAnchor(null)
  }

  return {
    isOpen: anchor !== null,
    rootRef,
    tooltipClassName: anchor
      ? `pointer-events-none absolute z-20 hidden w-96 transition duration-200 ease-sb-enter starting:opacity-0 motion-reduce:transition-none lg:block ${anchorClass[anchor.side]} ${anchorClass[anchor.edge]}`
      : "",
    handlers: {
      onMouseEnter: () => {
        clearTimeout(openTimerRef.current)
        openTimerRef.current = setTimeout(open, OPEN_DELAY_MS)
      },
      onMouseLeave: close,
      onFocus: open,
      onBlur: (event: FocusEvent<T>) => {
        if (!event.currentTarget.contains(event.relatedTarget)) close()
      },
      onKeyDown: (event: { key: string }) => event.key === "Escape" && close(),
    },
  }
}
