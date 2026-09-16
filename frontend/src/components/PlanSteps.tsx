const STEPS = ["기획안 확인", "유사 사례 검색", "사례 비교"]

export default function PlanSteps({ current }: { current: number }) {
  return (
    <ol
      aria-label="진행 단계"
      className="flex flex-wrap items-center gap-sb-4 border border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-3"
    >
      {STEPS.map((step, index) => (
        <li key={step} className="flex items-center gap-sb-4">
          {index > 0 && (
            <span aria-hidden="true" className="text-sb-ink-mute-2">
              →
            </span>
          )}
          <span
            aria-current={index === current ? "step" : undefined}
            className={
              index === current
                ? "border-b-2 border-sb-primary pb-sb-1 font-medium"
                : "text-sb-ink-mute"
            }
          >
            {index + 1} {step}
          </span>
        </li>
      ))}
    </ol>
  )
}
