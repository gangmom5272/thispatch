import type { CaseComparisonItem, SimilarCase } from "@/types"

interface GroupTone {
  dot: string
  head: string
  card: string
}

const commonTone: GroupTone = {
  dot: "bg-sb-pos",
  head: "border-sb-line-green",
  card: "border-sb-primary",
}

const differenceTone: GroupTone = {
  dot: "bg-sb-accent-yellow",
  head: "border-sb-line-amber",
  card: "border-sb-line-amber",
}

interface ComparisonGroupProps {
  title: string
  items: CaseComparisonItem[]
  tone: GroupTone
}

function ComparisonGroup({ title, items, tone }: ComparisonGroupProps) {
  return (
    <div className="flex flex-col gap-sb-3">
      <h3 className={`flex items-center gap-sb-2 border-b pb-sb-2 font-medium ${tone.head}`}>
        <span aria-hidden="true" className={`size-[11px] rounded-sm ${tone.dot}`} />
        {title}
      </h3>
      {items.length === 0 ? (
        <p className="text-sb-ink-mute">정리된 {title}이 없습니다.</p>
      ) : (
        <ul className="flex flex-col gap-sb-3">
          {items.map((item) => (
            <li
              key={item.title}
              className={`flex flex-col gap-sb-2 rounded-sb-control border bg-sb-canvas-night-soft px-sb-4 py-sb-3 ${tone.card}`}
            >
              <p className="font-medium">{item.title}</p>
              <p className="leading-relaxed text-sb-ink-mute">{item.description}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function ComparisonPanel({ comparison }: { comparison: SimilarCase["comparison"] }) {
  return (
    <section
      aria-labelledby="comparison-heading"
      className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
    >
      <div className="flex flex-wrap items-baseline gap-x-sb-3 gap-y-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
        <h2 id="comparison-heading" className="font-medium">
          공통점과 차이점
        </h2>
        <p className="text-sb-ink-mute">기획안 초안과 해당 패치의 공통점과 차이점을 비교합니다.</p>
        <ul className="flex items-center gap-sb-4 text-sb-ink-mute sm:ml-auto">
          <li className="flex items-center gap-sb-2">
            <span aria-hidden="true" className={`size-[11px] rounded-sm ${commonTone.dot}`} />
            공통점 {comparison.commonalities.length}
          </li>
          <li className="flex items-center gap-sb-2">
            <span aria-hidden="true" className={`size-[11px] rounded-sm ${differenceTone.dot}`} />
            차이점 {comparison.differences.length}
          </li>
        </ul>
      </div>
      <div className="grid grid-cols-1 gap-sb-4 p-sb-4 lg:grid-cols-2">
        <ComparisonGroup title="공통점" items={comparison.commonalities} tone={commonTone} />
        <ComparisonGroup title="차이점" items={comparison.differences} tone={differenceTone} />
      </div>
    </section>
  )
}
