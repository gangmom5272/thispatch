import { useId, useState } from "react"
import LoadMoreSentinel from "@/components/LoadMoreSentinel"
import type { CaseGroup, CaseOutcome } from "@/types"
import CaseCard from "./CaseCard"

const PAGE_SIZE = 4

const headTone: Record<CaseOutcome, string> = {
  NEGATIVE_SHIFT: "border-sb-neg text-sb-neg-text",
  NO_CHANGE: "border-sb-hairline-strong text-sb-ink-mute",
  POSITIVE_SHIFT: "border-sb-primary text-sb-pos-text",
}

const dotTone: Record<CaseOutcome, string> = {
  NEGATIVE_SHIFT: "bg-sb-neg",
  NO_CHANGE: "bg-sb-hairline-strong",
  POSITIVE_SHIFT: "bg-sb-pos",
}

const smallSampleNote: Record<CaseOutcome, string> = {
  NEGATIVE_SHIFT: "표본이 적어 공통 패턴을 요약하지 않습니다. 개별 사례의 조건을 확인하세요.",
  NO_CHANGE: "표본이 적어 공통 패턴을 요약하지 않습니다. 개별 사례의 조건을 확인하세요.",
  POSITIVE_SHIFT: "표본이 적어 공통 패턴을 요약하지 않습니다. 긍정 변화는 성공의 근거가 아닙니다.",
}

function ObservedPatterns({ group }: { group: CaseGroup }) {
  if (group.caseCount === 0) {
    return <p className="text-sb-ink-mute">이 결과군에 해당하는 사례가 없습니다.</p>
  }
  if (group.observedPatterns.length === 0) {
    return (
      <>
        <p className="font-medium text-sb-amber-text">{group.caseCount}건 · 소표본 해석 주의</p>
        <p className="text-sb-ink-mute">{smallSampleNote[group.outcome]}</p>
      </>
    )
  }
  return (
    <>
      <p className="font-medium text-sb-ink-mute">이 결과군에서 함께 관측된 변경 패턴</p>
      <ul className="flex flex-col gap-sb-1">
        {group.observedPatterns.map((pattern) => (
          <li key={pattern} className="flex items-center gap-sb-2 text-sb-ink-mute">
            <span
              aria-hidden="true"
              className={`size-1 shrink-0 rounded-full ${dotTone[group.outcome]}`}
            />
            {pattern}
          </li>
        ))}
      </ul>
    </>
  )
}

export default function OutcomeColumn({ group, gameId }: { group: CaseGroup; gameId: number }) {
  const headingId = useId()
  // ponytail: API가 사례를 한 번에 내려주므로 클라이언트에서 점진 노출. 커서 페이징이 생기면 useInfiniteQuery로 교체.
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)
  const visibleCases = group.cases.slice(0, visibleCount)
  const hasMore = visibleCount < group.cases.length

  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-sb-3 lg:row-span-3 lg:grid lg:grid-rows-subgrid"
    >
      <div
        className={`flex items-center justify-between border-b bg-sb-canvas-surface px-sb-4 py-sb-2 ${headTone[group.outcome]}`}
      >
        <h2 id={headingId} className="font-medium">
          {group.name}
        </h2>
        <span className="font-sb-mono text-sb-ink-mute tabular-nums">{group.caseCount}건</span>
      </div>
      <div className="flex flex-col gap-sb-2 rounded-sb-control border border-sb-hairline bg-sb-canvas-night-soft px-sb-4 py-sb-3">
        <ObservedPatterns group={group} />
      </div>
      {group.cases.length > 0 && (
        <div className="flex flex-col pt-sb-2">
          <ul className="flex flex-col gap-sb-3">
            {visibleCases.map((item) => (
              <CaseCard
                key={`${item.gameId}-${item.patchId}`}
                item={item}
                gameId={gameId}
                outcome={group.outcome}
                outcomeName={group.name}
              />
            ))}
          </ul>
          {hasMore && (
            <LoadMoreSentinel
              key={visibleCount}
              isLoading={false}
              onReach={() => setVisibleCount((count) => count + PAGE_SIZE)}
            />
          )}
        </div>
      )}
    </section>
  )
}
