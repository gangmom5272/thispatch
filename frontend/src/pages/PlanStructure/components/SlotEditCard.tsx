import Input from "@/components/Input"
import type { PatchChangeType, PlanSlot } from "@/types"

const CHANGE_TYPES: Array<{ value: PatchChangeType; label: string }> = [
  { value: "MODIFY", label: "변경" },
  { value: "ADD", label: "추가" },
  { value: "REMOVE", label: "삭제" },
  { value: "FIX", label: "버그 수정" },
  { value: "DEPRECATE", label: "지원 중단" },
]

const DIRECTIONS = ["INCREASE", "DECREASE", "NONE", "NOT_APPLICABLE", "UNKNOWN"]
const selectClass =
  "h-sb-control w-full rounded-sb-control border border-sb-hairline bg-sb-canvas px-sb-3 text-sb-ink focus-visible:outline-2 focus-visible:outline-sb-primary"

interface SlotEditCardProps {
  index: number
  slot: PlanSlot
  isEmpty: boolean
  onChange: (next: PlanSlot) => void
}

export default function SlotEditCard({ index, slot, isEmpty, onChange }: SlotEditCardProps) {
  const label = `슬롯 ${index}`
  const isUnknown = slot.targetRole === "UNKNOWN"
  const update = (patch: Partial<PlanSlot>) => onChange({ ...slot, ...patch })

  return (
    <li
      className={`flex flex-col gap-sb-2 rounded-sb-control border bg-sb-canvas px-sb-3 py-sb-3 ${isUnknown ? "border-sb-line-amber" : "border-sb-hairline"}`}
    >
      <p className="flex items-center gap-sb-2 font-sb-mono text-sb-ink-mute tabular-nums">
        {label}
        {isEmpty && (
          <span className="rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 font-sb-sans text-sb-caption text-sb-ink-mute-2">
            비어 있음
          </span>
        )}
      </p>
      <div className="grid grid-cols-[auto_1fr] items-center gap-x-sb-3 gap-y-sb-2">
        <span className="text-sb-ink-mute">대상</span>
        <div className="flex gap-sb-2">
          <Input
            aria-label={`${label} 대상 이름`}
            value={slot.targetName}
            placeholder="예: Axebot"
            onChange={(event) => update({ targetName: event.target.value })}
            className="min-w-0 flex-1"
          />
          <Input
            aria-label={`${label} 대상 역할`}
            value={slot.targetRole}
            placeholder="예: ENEMY"
            onChange={(event) => update({ targetRole: event.target.value })}
            className="w-36"
          />
        </div>
        <span className="text-sb-ink-mute">속성</span>
        <Input
          aria-label={`${label} 속성`}
          value={slot.attribute}
          placeholder="예: HP"
          onChange={(event) => update({ attribute: event.target.value })}
        />
        <span className="text-sb-ink-mute">변경 종류</span>
        <select
          aria-label={`${label} 변경 종류`}
          className={selectClass}
          value={slot.changeType}
          onChange={(event) => {
            const selected = CHANGE_TYPES.find((item) => item.value === event.target.value)
            if (!selected) return
            let direction = "NONE"
            if (selected.value === "MODIFY") direction = "UNKNOWN"
            if (selected.value === "FIX") direction = "NOT_APPLICABLE"
            update({ changeType: selected.value, direction })
          }}
        >
          {CHANGE_TYPES.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
        <span className="text-sb-ink-mute">방향</span>
        <select
          aria-label={`${label} 변경 방향`}
          className={selectClass}
          value={slot.direction}
          onChange={(event) => update({ direction: event.target.value })}
        >
          {DIRECTIONS.map((direction) => (
            <option key={direction} value={direction}>
              {direction}
            </option>
          ))}
        </select>
        <span className="text-sb-ink-mute">변경 폭</span>
        <Input
          aria-label={`${label} 변경 폭`}
          value={slot.magnitude ?? ""}
          placeholder="예: +20%"
          onChange={(event) => update({ magnitude: event.target.value || null })}
        />
        <span className="text-sb-ink-mute">범위</span>
        <Input
          aria-label={`${label} 적용 범위`}
          value={slot.scope ?? ""}
          placeholder="예: 고통 4 이상"
          onChange={(event) => update({ scope: event.target.value || null })}
        />
      </div>
    </li>
  )
}
