import { useId, useRef, useState, type FocusEvent, type KeyboardEvent } from "react"
import type { GameTag } from "@/types"

interface GenreFilterDropdownProps {
  genres: GameTag[]
  excludedIds: number[]
  isLoading: boolean
  onChange: (excludedIds: number[]) => void
}

const bulkButtonClass =
  "cursor-pointer rounded-sb-tag text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary aria-disabled:cursor-not-allowed aria-disabled:opacity-50"

export default function GenreFilterDropdown({
  genres,
  excludedIds,
  isLoading,
  onChange,
}: GenreFilterDropdownProps) {
  const [isOpen, setIsOpen] = useState(false)
  const panelId = useId()
  const triggerRef = useRef<HTMLButtonElement>(null)

  const selectedCount = genres.filter((genre) => !excludedIds.includes(genre.id)).length
  const allIds = genres.map((genre) => genre.id)

  const toggleGenre = (id: number) =>
    onChange(
      excludedIds.includes(id)
        ? excludedIds.filter((genreId) => genreId !== id)
        : [...excludedIds, id],
    )

  const handleBlur = (event: FocusEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setIsOpen(false)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== "Escape" || !isOpen) return
    event.preventDefault()
    setIsOpen(false)
    triggerRef.current?.focus()
  }

  return (
    <div className="relative" onBlur={handleBlur} onKeyDown={handleKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={isOpen}
        aria-controls={isOpen ? panelId : undefined}
        disabled={isLoading || genres.length === 0}
        onClick={() => setIsOpen((open) => !open)}
        className="flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
      >
        장르 필터
        <span className="font-sb-mono text-sb-ink-mute tabular-nums">
          {isLoading ? "불러오는 중…" : `${selectedCount} / ${genres.length}`}
        </span>
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          className={`size-4 text-sb-ink-mute ${isOpen ? "rotate-180" : ""}`}
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {isOpen && (
        <div
          id={panelId}
          role="group"
          aria-label="장르 필터"
          tabIndex={-1}
          className="absolute top-full left-0 z-20 mt-sb-1 flex w-72 max-w-[calc(100vw-32px)] flex-col rounded-sb-control border border-sb-hairline-strong bg-sb-canvas shadow-sb-popover outline-none"
        >
          <div className="flex items-center gap-sb-3 border-b border-sb-hairline px-sb-3 py-sb-2">
            <button
              type="button"
              aria-disabled={excludedIds.length === 0}
              onClick={() => onChange([])}
              className={bulkButtonClass}
            >
              전체 선택
            </button>
            <button
              type="button"
              aria-disabled={selectedCount === 0}
              onClick={() => onChange(allIds)}
              className={bulkButtonClass}
            >
              전체 해제
            </button>
          </div>
          <ul className="max-h-80 overflow-y-auto py-sb-1">
            {genres.map((genre) => (
              <li key={genre.id}>
                <label className="flex h-9 cursor-pointer items-center gap-sb-2 px-sb-3 hover:bg-sb-canvas-active has-focus-visible:bg-sb-canvas-active">
                  <input
                    type="checkbox"
                    className="accent-sb-primary"
                    checked={!excludedIds.includes(genre.id)}
                    onChange={() => toggleGenre(genre.id)}
                  />
                  {genre.name}
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
