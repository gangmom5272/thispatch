import { formatPercent } from "@/lib/format"

interface RateBarProps {
  before: number
  after: number
  className?: string
}

export default function RateBar({ before, after, className = "h-[10px]" }: RateBarProps) {
  const kept = Math.max(0, Math.min(before, after))
  const changed = Math.abs(after - before)
  return (
    <svg
      role="img"
      aria-label={`긍정률 ${formatPercent(before)}에서 ${formatPercent(after)}로 변화`}
      className={`w-full ${className}`}
      preserveAspectRatio="none"
    >
      <rect width="100%" height="100%" rx="4" className="fill-sb-canvas-soft" />
      <rect width={`${kept}%`} height="100%" className="fill-sb-hairline-strong" />
      <rect
        x={`${kept}%`}
        width={`${changed}%`}
        height="100%"
        className={after >= before ? "fill-sb-pos" : "fill-sb-neg"}
      />
    </svg>
  )
}
