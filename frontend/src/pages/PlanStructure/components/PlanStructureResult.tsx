import { Fragment, useId, type ReactNode } from "react"
import Button from "@/components/Button"
import type { PlanSlot, PlanStructure } from "@/types"

const cardClass = "rounded-sb-control border bg-sb-canvas"

function PencilIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-4"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  )
}

function isUnknownRole(role: string): boolean {
  return role === "UNKNOWN"
}

function toneClass(isUnknown: boolean): string {
  return isUnknown ? "border-sb-line-amber" : "border-sb-hairline"
}

function KeyValueList({ rows, muted }: { rows: Array<[string, string]>; muted?: boolean }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-sb-3 gap-y-sb-2">
      {rows.map(([key, value], index) => (
        <Fragment key={key}>
          <dt className="text-sb-ink-mute">{key}</dt>
          <dd className={`${index === 0 ? "font-medium" : ""} ${muted ? "text-sb-ink-mute" : ""}`}>
            {value}
          </dd>
        </Fragment>
      ))}
    </dl>
  )
}

interface PanelProps {
  step: number
  title: string
  subtitle: string
  aside?: string
  children: ReactNode
}

function Panel({ step, title, subtitle, aside, children }: PanelProps) {
  const headingId = useId()
  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
    >
      <div className="flex flex-col gap-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
        <div className="flex items-center gap-sb-2">
          <span
            aria-hidden="true"
            className="flex size-6 items-center justify-center rounded-full border border-sb-hairline-strong bg-sb-canvas-soft font-sb-mono text-sb-primary"
          >
            {step}
          </span>
          <h2 id={headingId} className="font-medium">
            {title}
          </h2>
          {aside && (
            <span className="ml-auto font-sb-mono text-sb-ink-mute tabular-nums">{aside}</span>
          )}
        </div>
        <p className="text-sb-ink-mute">{subtitle}</p>
      </div>
      <div className="flex flex-1 flex-col gap-sb-3 p-sb-4">{children}</div>
    </section>
  )
}

function SlotCard({ index, slot }: { index: number; slot: PlanSlot }) {
  const isUnknown = isUnknownRole(slot.targetRole)
  return (
    <li className={`${cardClass} ${toneClass(isUnknown)} flex flex-col gap-sb-2 px-sb-3 py-sb-3`}>
      <p className="font-sb-mono text-sb-ink-mute tabular-nums">슬롯 {index}</p>
      <KeyValueList
        muted={isUnknown}
        rows={[
          ["대상", `${slot.targetName} · ${slot.targetRole}`],
          ["속성", slot.attribute],
          ["변경", slot.magnitude ? `${slot.direction} ${slot.magnitude}` : slot.direction],
          ["범위", slot.scope ?? "범위 미확인"],
        ]}
      />
    </li>
  )
}

interface PlanStructureResultProps {
  structure: PlanStructure
  onSearchCases: () => void
}

export default function PlanStructureResult({
  structure,
  onSearchCases,
}: PlanStructureResultProps) {
  const { entities, slots, restatement } = structure
  const hasUnknownEntity = entities.some((entity) => isUnknownRole(entity.role))

  return (
    <div className="grid grid-cols-1 gap-sb-4 lg:grid-cols-[5fr_9fr_6fr]">
      <Panel
        step={1}
        title="고유명사 탐지"
        subtitle="게임별 단어사전에서 변경 대상의 의미를 조회합니다"
      >
        {entities.length === 0 ? (
          <p className="text-sb-ink-mute">탐지된 고유명사가 없습니다.</p>
        ) : (
          <ul className="flex flex-col gap-sb-2">
            {entities.map((entity) => {
              const isUnknown = isUnknownRole(entity.role)
              return (
                <li
                  key={entity.id}
                  className={`${cardClass} ${toneClass(isUnknown)} flex flex-wrap items-center gap-sb-2 px-sb-3 py-sb-2`}
                >
                  <span className="font-sb-mono font-medium">{entity.name}</span>
                  <span aria-hidden="true" className="text-sb-ink-mute">
                    →
                  </span>
                  <span
                    className={`font-medium ${isUnknown ? "text-sb-amber-text" : "text-sb-pos-text"}`}
                  >
                    {entity.role}
                  </span>
                </li>
              )
            })}
          </ul>
        )}
        {hasUnknownEntity && (
          <p className="mt-auto text-sb-ink-mute">
            UNKNOWN 항목은 폐기하지 않고 유지하되 검색 순위에서 낮게 반영됩니다.
          </p>
        )}
      </Panel>

      <Panel
        step={2}
        title="변경 슬롯 추출"
        subtitle="수치 델타만으로 버프·너프를 판단하지 않고 대상의 역할부터 확인합니다"
        aside={`${slots.length}개 슬롯`}
      >
        {slots.length === 0 ? (
          <p className="text-sb-ink-mute">추출된 변경 슬롯이 없습니다.</p>
        ) : (
          <ul className="flex flex-col gap-sb-2">
            {slots.map((slot, index) => (
              <SlotCard key={slot.id} index={index + 1} slot={slot} />
            ))}
          </ul>
        )}
      </Panel>

      <Panel
        step={3}
        title="재진술 확인"
        subtitle="시스템이 이해한 내용입니다. 다르면 기획안을 수정해 다시 구조화하세요."
      >
        <p className="leading-relaxed">{restatement.text}</p>
        <hr className="border-sb-hairline" />
        <KeyValueList
          rows={[
            ["변경 대상", restatement.highlights.primaryRole],
            ["변경 속성", restatement.highlights.attributes.join(" · ")],
            ["변경 방향", restatement.highlights.direction],
            ["적용 범위", restatement.highlights.scope ?? "범위 미확인"],
          ]}
        />
        {restatement.warnings.length > 0 && (
          <ul aria-label="주의 사항" className="flex flex-col gap-sb-2">
            {restatement.warnings.map((warning) => (
              <li
                key={`${warning.code}-${warning.entityName ?? ""}`}
                className="flex gap-sb-2 rounded-sb-control border border-sb-line-amber bg-sb-tint-amber px-sb-3 py-sb-2 text-sb-ink-mute"
              >
                <span aria-hidden="true" className="text-sb-amber-text">
                  ⚠
                </span>
                {warning.message}
              </li>
            ))}
          </ul>
        )}
        <div className="mt-auto flex flex-col gap-sb-2 pt-sb-3">
          <Button variant="secondary" disabled className="w-full">
            <PencilIcon />
            슬롯 직접 수정
          </Button>
          <Button variant="primary" onClick={onSearchCases} className="w-full">
            유사 사례 검색
            <span aria-hidden="true">→</span>
          </Button>
        </div>
      </Panel>
    </div>
  )
}
