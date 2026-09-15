const PREFETCH_ROOT_MARGIN = "240px"

interface LoadMoreSentinelProps {
  isLoading: boolean
  onReach: () => void
  loadingLabel?: string
}

export default function LoadMoreSentinel({
  isLoading,
  onReach,
  loadingLabel = "다음 게임을 불러오는 중…",
}: LoadMoreSentinelProps) {
  const observe = (node: HTMLDivElement | null) => {
    if (!node) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) onReach()
      },
      { rootMargin: PREFETCH_ROOT_MARGIN },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }

  return (
    <div ref={observe} aria-live="polite" className="flex justify-center py-sb-4 text-sb-ink-mute">
      {isLoading ? loadingLabel : ""}
    </div>
  )
}
