import { useSyncExternalStore } from "react"
import { usePrefersReducedMotion } from "@/hooks/usePrefersReducedMotion"

/** 이만큼 내려간 뒤에야 버튼을 보여 준다. 첫 화면에서는 필요 없기 때문이다. */
const SHOW_AFTER_PX = 600

function subscribe(onChange: () => void) {
  window.addEventListener("scroll", onChange, { passive: true })
  return () => window.removeEventListener("scroll", onChange)
}

/**
 * 무한 스크롤 목록 페이지의 우측 하단 플로팅 버튼.
 * 일정 거리 아래로 내려가면 나타나고, 누르면 페이지 맨 위로 올라간다.
 */
export default function ScrollToTopButton() {
  const isScrolledDown = useSyncExternalStore(
    subscribe,
    () => window.scrollY > SHOW_AFTER_PX,
    () => false,
  )
  const prefersReducedMotion = usePrefersReducedMotion()

  if (!isScrolledDown) return null

  return (
    <button
      type="button"
      aria-label="맨 위로"
      onClick={() =>
        window.scrollTo({ top: 0, behavior: prefersReducedMotion ? "auto" : "smooth" })
      }
      className="fixed right-sb-4 bottom-sb-4 z-20 flex size-sb-control cursor-pointer items-center justify-center rounded-sb-control border border-sb-hairline-strong bg-sb-canvas text-sb-ink shadow-sb-popover transition duration-200 ease-sb-enter hover:border-sb-primary hover:text-sb-primary-text focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-canvas-active motion-reduce:transition-none md:right-sb-6 md:bottom-sb-6 starting:translate-y-2 starting:opacity-0"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 24 24"
        className="size-5"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="m6 15 6-6 6 6" />
      </svg>
    </button>
  )
}
