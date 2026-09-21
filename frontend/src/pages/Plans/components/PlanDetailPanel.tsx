import { useNavigate } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { usePatchPlanDetail } from "@/hooks/queries/patchQueries"
import { formatSeoulDateTime } from "@/lib/seoulDate"
import { gameCasesPath } from "@/router/paths"
import type { ConfirmedSlot, PatchChangeType, PlanSlot } from "@/types"

const CHANGE_TYPE_LABELS: Record<PatchChangeType, string> = {
  ADD: "추가",
  REMOVE: "제거",
  MODIFY: "조정",
  FIX: "수정",
  DEPRECATE: "지원 중단",
}

const DIRECTION_LABELS: Record<string, string> = {
  INCREASE: "증가",
  DECREASE: "감소",
}

const panelClass = "rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
const labelClass = "text-sb-caption text-sb-ink-mute-2"
const cellClass = "px-sb-5 py-sb-3 text-left align-top"

function isUnknownRole(role: string): boolean {
  return role === "UNKNOWN"
}

function targetLabel(target: ConfirmedSlot["target"]): string {
  return `${target.name} · ${isUnknownRole(target.role) ? "미확인" : target.role}`
}

function changeLabel(slot: ConfirmedSlot): string {
  const direction = DIRECTION_LABELS[slot.direction]
  if (!direction) return `${CHANGE_TYPE_LABELS[slot.changeType]} · 방향 불명`
  return slot.magnitude ? `${direction} ${slot.magnitude}` : direction
}

function toPlanSlot(slot: ConfirmedSlot, index: number): PlanSlot {
  return {
    id: index + 1,
    targetName: slot.target.name,
    targetRole: slot.target.role,
    attribute: slot.attribute,
    changeType: slot.changeType,
    direction: slot.direction,
    magnitude: slot.magnitude,
    scope: slot.scope,
    editable: false,
  }
}

function AlertIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="mt-0.5 size-[18px] shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="10" />
      <path d="M12 8v4" />
      <path d="M12 16h.01" />
    </svg>
  )
}

function SlotTable({ slots }: { slots: ConfirmedSlot[] }) {
  return (
    <div className={`${panelClass} overflow-x-auto`}>
      <table className="w-full min-w-[700px] border-collapse">
        <thead>
          <tr className="border-b border-sb-hairline bg-sb-canvas-soft text-sb-caption text-sb-ink-mute">
            <th scope="col" className={`${cellClass} w-[230px] font-normal`}>
              변경 대상
            </th>
            <th scope="col" className={`${cellClass} w-[140px] font-normal`}>
              속성
            </th>
            <th scope="col" className={`${cellClass} w-[210px] font-normal`}>
              변경 내용
            </th>
            <th scope="col" className={`${cellClass} font-normal`}>
              적용 범위
            </th>
          </tr>
        </thead>
        <tbody>
          {slots.map((slot, index) => {
            const unknownTarget = isUnknownRole(slot.target.role)
            const unknownDirection = !DIRECTION_LABELS[slot.direction]
            return (
              <tr
                key={`${slot.target.name}-${slot.attribute}-${index}`}
                className="border-b border-sb-hairline last:border-b-0"
              >
                <th
                  scope="row"
                  className={`${cellClass} border-l-[3px] font-normal ${
                    unknownTarget
                      ? "border-l-sb-mark text-sb-amber-text"
                      : "border-l-transparent text-sb-ink"
                  }`}
                >
                  {targetLabel(slot.target)}
                </th>
                <td className={cellClass}>{slot.attribute}</td>
                <td className={`${cellClass} ${unknownDirection ? "text-sb-amber-text" : ""}`}>
                  {changeLabel(slot)}
                </td>
                <td className={`${cellClass} ${slot.scope ? "" : "text-sb-amber-text"}`}>
                  {slot.scope ?? "범위 미확인"}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default function PlanDetailPanel({ planId }: { planId: number }) {
  const navigate = useNavigate()
  const plan = usePatchPlanDetail(planId)

  if (plan.isPending) {
    return (
      <div role="status" aria-live="polite" className={`${panelClass} p-sb-6 text-sb-ink-mute`}>
        기획안 내역을 불러오는 중입니다…
      </div>
    )
  }

  if (plan.isError) {
    const notFound = isApiError(plan.error) && plan.error.status === 404
    return (
      <div role="alert" className={`${panelClass} flex flex-col items-start gap-sb-3 p-sb-6`}>
        <p>
          {notFound
            ? "기획안 내역을 찾을 수 없습니다. 목록에서 다른 내역을 선택해 주세요."
            : isApiError(plan.error)
              ? plan.error.message
              : "기획안 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
        </p>
        {!notFound && (
          <Button variant="secondary" onClick={() => plan.refetch()} disabled={plan.isFetching}>
            다시 시도
          </Button>
        )}
      </div>
    )
  }

  const { gameId, gameTitle, rawText, restatement, genreIds, confirmedSlots, createdAt } = plan.data
  const unknownTargets = [
    ...new Set(
      confirmedSlots
        .filter((slot) => isUnknownRole(slot.target.role))
        .map((slot) => slot.target.name),
    ),
  ]

  return (
    <div className="flex flex-col gap-sb-8">
      <div className="flex flex-col gap-sb-2">
        <p className="text-sb-caption font-medium text-sb-primary-text">대상 게임</p>
        <h2 className="text-sb-heading font-medium">{gameTitle}</h2>
        <p className="font-sb-mono text-sb-caption text-sb-ink-mute tabular-nums">
          {formatSeoulDateTime(createdAt)} 입력 · 변경점 {confirmedSlots.length}개
          {unknownTargets.length > 0 && ` · 미확인 ${unknownTargets.length}개`}
        </p>
        <div className="pt-sb-2">
          <Button
            variant="primary"
            disabled={confirmedSlots.length === 0}
            onClick={() =>
              navigate(gameCasesPath(gameId), {
                state: { planId, slots: confirmedSlots.map(toPlanSlot), genreIds },
              })
            }
          >
            이 기획안으로 유사 사례 검색
          </Button>
        </div>
      </div>

      <section className="flex flex-col gap-sb-1 border-l-2 border-sb-primary pl-sb-4">
        <h3 className="text-sb-caption font-medium text-sb-primary-text">기획안 해석</h3>
        <p className="text-sb-ink-mute">{restatement.text}</p>
      </section>

      <section className="flex flex-col gap-sb-3">
        <h3 className={labelClass}>입력 원문</h3>
        <p className={`${panelClass} p-sb-5 leading-relaxed whitespace-pre-line`}>{rawText}</p>
      </section>

      <section className="flex flex-col gap-sb-3">
        <h3 className={labelClass}>
          구조화된 변경점 <span className="font-sb-mono tabular-nums">{confirmedSlots.length}</span>
        </h3>
        {confirmedSlots.length === 0 ? (
          <p className={`${panelClass} p-sb-5 text-sb-ink-mute`}>
            확정된 변경점이 없는 검색 내역입니다. 기획안을 다시 입력해 변경점을 확정해 주세요.
          </p>
        ) : (
          <SlotTable slots={confirmedSlots} />
        )}
        {unknownTargets.length > 0 && (
          <p className="flex items-start gap-sb-2 rounded-sb-control border border-sb-line-amber bg-sb-tint-amber px-sb-4 py-sb-3 text-sb-caption text-sb-amber-text">
            <AlertIcon />
            <span>
              {unknownTargets.join(", ")}의 의미가 확인되지 않아 변경 방향과 적용 범위가 확정되지
              않았습니다. 기획안을 다시 입력해 대상을 명확히 하면 변경점이 확정됩니다.
            </span>
          </p>
        )}
      </section>
    </div>
  )
}
