import Input from "@/components/Input"
import type { PlanDirection, PlanSlot } from "@/types"

const DIRECTIONS: PlanDirection[] = ["INCREASE", "DECREASE", "MODIFY"]

const selectClass =
  "h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-2 text-sb-body text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

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
        <span className="text-sb-ink-mute">변경</span>
        <div className="flex gap-sb-2">
          <select
            aria-label={`${label} 변경 방향`}
            value={slot.direction}
            onChange={(event) =>
              update({
                direction:
                  DIRECTIONS.find((direction) => direction === event.target.value) ??
                  slot.direction,
              })
            }
            className={selectClass}
          >
            {DIRECTIONS.map((direction) => (
              <option key={direction} value={direction}>
                {direction}
              </option>
            ))}
          </select>
          <Input
            aria-label={`${label} 변경 폭`}
            value={slot.magnitude ?? ""}
            placeholder="예: +20%"
            onChange={(event) => update({ magnitude: event.target.value || null })}
            className="min-w-0 flex-1"
          />
        </div>
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
