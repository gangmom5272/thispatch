const POSITIVE_RATE_MIN = 80
const NEUTRAL_RATE_MIN = 60

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

export function StarIcon({ filled }: { filled: boolean }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-5"
      fill={filled ? "currentColor" : "none"}
      stroke="currentColor"
      strokeWidth="2"
      strokeLinejoin="round"
    >
      <path d="m12 3 2.7 5.8 6.3.8-4.6 4.4 1.2 6.3L12 17.3 6.4 20.3l1.2-6.3L3 9.6l6.3-.8Z" />
    </svg>
  )
}

/** 긍정률 수치와 막대. 80% 이상 긍정, 60% 이상 중립, 그 아래는 부정 톤. */
export function PositiveRate({ rate }: { rate: number }) {
  const tone = rateTone(rate)
  return (
    <div className="mt-auto flex flex-col gap-sb-2">
      <div className="flex items-baseline justify-between">
        <span className="text-sb-lead text-sb-ink-mute">긍정률</span>
        <span className={`font-sb-mono text-sb-title tabular-nums ${tone.text}`}>{rate}%</span>
      </div>
      <progress
        value={rate}
        max={100}
        aria-hidden="true"
        className={`h-1.5 w-full appearance-none overflow-hidden rounded-full border-0 bg-sb-canvas-soft [&::-webkit-progress-bar]:bg-transparent ${tone.bar}`}
      />
    </div>
  )
}
