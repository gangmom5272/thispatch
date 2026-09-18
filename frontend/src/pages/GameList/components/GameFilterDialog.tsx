import { useEffect, useId, useRef, useState, type FormEvent } from "react"
import Input from "../../../components/Input"
import {
  DEFAULT_GAME_FILTER,
  GAME_RANGE_FILTERS,
  GAME_SORT_OPTIONS,
  type GameRangeFilterGroup,
  type GameRangeKey,
} from "../../../constants/games"
import type { GameFilterConditions } from "../../../types"
import GenreCombobox from "./GenreCombobox"

const DEVELOPER_MAX_LENGTH = 500

/** 빈 입력은 undefined(파라미터 생략), 그 외는 정수만 허용한다. */
function parseRangeInput(value: string): number | undefined {
  if (value === "") return undefined
  const n = Number(value)
  return Number.isInteger(n) ? n : undefined
}

interface GameFilterDialogProps {
  initial: GameFilterConditions
  onApply: (next: GameFilterConditions) => void
  onClose: () => void
}

const optionClass =
  "flex cursor-pointer items-center gap-sb-2 border border-sb-hairline px-sb-3 text-sb-ink-mute has-checked:border-sb-hairline-strong has-checked:bg-sb-canvas-active has-checked:text-sb-ink has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-sb-primary"

export default function GameFilterDialog({ initial, onApply, onClose }: GameFilterDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const developerId = useId()
  const [draft, setDraft] = useState(initial)

  useEffect(() => {
    dialogRef.current?.showModal()
  }, [])

  const close = () => dialogRef.current?.close()

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onApply(draft)
    close()
  }

  return (
    <dialog
      ref={dialogRef}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) close()
      }}
      className="m-auto w-full max-w-xl rounded-sb-modal border border-sb-hairline-strong bg-sb-canvas-surface p-0 font-sb-sans text-sb-body text-sb-ink shadow-sb-modal backdrop:bg-sb-scrim"
    >
      <form onSubmit={handleSubmit} className="flex flex-col">
        <div className="sticky top-0 z-20 flex items-center justify-between border-b border-sb-hairline bg-sb-canvas-surface px-sb-6 py-sb-4">
          <h2 className="text-sb-title font-medium">필터</h2>
          <button
            type="button"
            onClick={close}
            aria-label="필터 닫기"
            className="cursor-pointer rounded-sb-tag px-sb-2 text-sb-title leading-none text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            ×
          </button>
        </div>

        <div className="flex flex-col gap-sb-6 p-sb-6">
          <fieldset>
            <legend className="mb-sb-3 flex flex-wrap gap-sb-2">
              장르
              <span className="text-sb-ink-mute">여러 개 선택 가능</span>
            </legend>
            <GenreCombobox
              selectedIds={draft.genreIds}
              onChange={(genreIds) => setDraft((prev) => ({ ...prev, genreIds }))}
            />
          </fieldset>

          {GAME_RANGE_FILTERS.map((group) => (
            <RangeFieldset
              key={group.from}
              group={group}
              from={draft[group.from]}
              to={draft[group.to]}
              onChange={(key, value) => setDraft((prev) => ({ ...prev, [key]: value }))}
            />
          ))}

          <div className="flex flex-col gap-sb-3">
            <label htmlFor={developerId} className="flex flex-wrap gap-sb-2">
              개발사
            </label>
            <Input
              id={developerId}
              type="search"
              value={draft.developer ?? ""}
              maxLength={DEVELOPER_MAX_LENGTH}
              placeholder="예: Valve"
              onChange={(event) =>
                setDraft((prev) => ({ ...prev, developer: event.target.value || undefined }))
              }
            />
          </div>

          <fieldset>
            <legend className="mb-sb-3 flex flex-wrap gap-sb-2">
              정렬
              <span className="text-sb-ink-mute">하나만 선택</span>
            </legend>
            <div className="grid grid-cols-1 gap-sb-3 sm:grid-cols-2">
              {GAME_SORT_OPTIONS.map((option) => (
                <label
                  key={option.value}
                  className={`${optionClass} h-sb-control rounded-sb-control`}
                >
                  <input
                    type="radio"
                    name="sort"
                    value={option.value}
                    className="accent-sb-primary"
                    checked={draft.sort === option.value}
                    onChange={() => setDraft((prev) => ({ ...prev, sort: option.value }))}
                  />
                  {option.label}
                </label>
              ))}
            </div>
          </fieldset>
        </div>

        <div className="sticky bottom-0 flex items-center gap-sb-2 border-t border-sb-hairline bg-sb-canvas-surface px-sb-6 py-sb-4">
          <button
            type="button"
            onClick={() => setDraft(DEFAULT_GAME_FILTER)}
            className="mr-auto cursor-pointer rounded-sb-control text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            초기화
          </button>
          <button
            type="button"
            onClick={close}
            className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            취소
          </button>
          <button
            type="submit"
            className="h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-primary-deep"
          >
            적용
          </button>
        </div>
      </form>
    </dialog>
  )
}

interface RangeFieldsetProps {
  group: GameRangeFilterGroup
  from: number | undefined
  to: number | undefined
  onChange: (key: GameRangeKey, value: number | undefined) => void
}

/** 최솟값·최댓값 한 쌍. 하한>상한은 네이티브 min/max 검증으로 제출을 막는다. */
function RangeFieldset({ group, from, to, onChange }: RangeFieldsetProps) {
  const id = useId()
  const inputClass = "w-full font-sb-mono tabular-nums"
  return (
    <fieldset>
      <legend className="mb-sb-3 flex flex-wrap gap-sb-2">
        {group.label}
        <span className="text-sb-ink-mute">단위 {group.unit}</span>
      </legend>
      <div className="grid grid-cols-2 gap-sb-3">
        <div className="flex flex-col gap-sb-1">
          <label htmlFor={`${id}-from`} className="text-sb-caption text-sb-ink-mute">
            최소
          </label>
          <Input
            id={`${id}-from`}
            type="number"
            inputMode="numeric"
            step={1}
            min={group.min}
            max={to ?? group.max}
            value={from ?? ""}
            onChange={(event) => onChange(group.from, parseRangeInput(event.target.value))}
            className={inputClass}
          />
        </div>
        <div className="flex flex-col gap-sb-1">
          <label htmlFor={`${id}-to`} className="text-sb-caption text-sb-ink-mute">
            최대
          </label>
          <Input
            id={`${id}-to`}
            type="number"
            inputMode="numeric"
            step={1}
            min={from ?? group.min}
            max={group.max}
            value={to ?? ""}
            onChange={(event) => onChange(group.to, parseRangeInput(event.target.value))}
            className={inputClass}
          />
        </div>
      </div>
    </fieldset>
  )
}
