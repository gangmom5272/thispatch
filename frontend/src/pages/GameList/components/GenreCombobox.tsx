import { useId, useState, type KeyboardEvent } from "react"
import { GAME_GENRES } from "../../../constants/games"
import type { GameTag } from "../../../types"

interface GenreComboboxProps {
  selectedIds: number[]
  onChange: (next: number[]) => void
}

function HighlightedName({ name, query }: { name: string; query: string }) {
  const start = query ? name.toLowerCase().indexOf(query.toLowerCase()) : -1
  if (start < 0) return <>{name}</>
  const end = start + query.length
  return (
    <>
      {name.slice(0, start)}
      <span className="text-sb-primary">{name.slice(start, end)}</span>
      {name.slice(end)}
    </>
  )
}

export default function GenreCombobox({ selectedIds, onChange }: GenreComboboxProps) {
  const [query, setQuery] = useState("")
  const [isOpen, setIsOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)
  const listboxId = useId()

  const trimmedQuery = query.trim()
  const selected = GAME_GENRES.filter((genre) => selectedIds.includes(genre.id))
  const available = GAME_GENRES.filter((genre) => !selectedIds.includes(genre.id))
  const matches = trimmedQuery
    ? available.filter((genre) => genre.name.toLowerCase().includes(trimmedQuery.toLowerCase()))
    : available
  const hasNoMatch = trimmedQuery.length > 0 && matches.length === 0
  const defaultActive = trimmedQuery && matches.length > 0 ? 0 : -1
  const active = activeIndex >= 0 && activeIndex < matches.length ? activeIndex : defaultActive
  const optionId = (index: number) => `${listboxId}-option-${index}`

  const addGenre = (genre: GameTag) => {
    onChange([...selectedIds, genre.id])
    setQuery("")
    setActiveIndex(-1)
  }

  const removeGenre = (id: number) => onChange(selectedIds.filter((genreId) => genreId !== id))

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault()
        setIsOpen(true)
        if (matches.length > 0) setActiveIndex((active + 1) % matches.length)
        break
      case "ArrowUp":
        event.preventDefault()
        if (matches.length > 0) setActiveIndex(active <= 0 ? matches.length - 1 : active - 1)
        break
      case "Enter": {
        event.preventDefault()
        const genre = matches[active]
        if (genre) addGenre(genre)
        break
      }
      case "Escape":
        if (isOpen) {
          event.preventDefault()
          setIsOpen(false)
        }
        break
    }
  }

  const fieldTone = hasNoMatch
    ? "border-sb-line-red"
    : "border-sb-hairline-cool focus-within:border-sb-primary"

  return (
    <div className="relative">
      <div
        className={`flex min-h-sb-control flex-wrap items-center gap-sb-1 rounded-sb-control border bg-sb-canvas-soft px-sb-2 py-sb-1 ${fieldTone}`}
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          className="size-4 shrink-0 text-sb-ink-mute"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        {selected.map((genre) => (
          <span
            key={genre.id}
            className="flex h-7 items-center gap-sb-1 rounded-sb-tag border border-sb-hairline-strong bg-sb-canvas-active pl-sb-2"
          >
            {genre.name}
            <button
              type="button"
              aria-label={`${genre.name} 장르 해제`}
              onClick={() => removeGenre(genre.id)}
              className="cursor-pointer rounded-sb-tag px-sb-1 text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-sb-primary"
            >
              ×
            </button>
          </span>
        ))}
        <input
          type="text"
          role="combobox"
          aria-label="장르 입력"
          autoComplete="off"
          aria-expanded={isOpen}
          aria-controls={isOpen ? listboxId : undefined}
          aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? optionId(active) : undefined}
          aria-invalid={hasNoMatch || undefined}
          value={query}
          placeholder={selected.length > 0 ? "장르 추가" : "장르 입력"}
          onChange={(event) => {
            setQuery(event.target.value)
            setIsOpen(true)
            setActiveIndex(-1)
          }}
          onFocus={() => setIsOpen(true)}
          onBlur={() => setIsOpen(false)}
          onKeyDown={handleKeyDown}
          className={`h-7 min-w-24 flex-1 bg-transparent px-sb-1 outline-none placeholder:text-sb-ink-mute ${hasNoMatch ? "text-sb-neg-text" : "text-sb-ink"}`}
        />
        <button
          type="button"
          tabIndex={-1}
          aria-label={isOpen ? "장르 목록 닫기" : "장르 목록 열기"}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => setIsOpen((open) => !open)}
          className="cursor-pointer px-sb-1 text-sb-ink-mute hover:text-sb-ink"
        >
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            className={`size-4 ${isOpen ? "rotate-180" : ""}`}
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </button>
      </div>

      {isOpen && hasNoMatch && (
        <p
          role="status"
          aria-live="polite"
          className="absolute inset-x-0 top-full z-10 mt-sb-1 flex items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas p-sb-3 text-sb-ink-mute shadow-sb-popover"
        >
          <span aria-hidden="true">ⓘ</span>
          일치하는 장르가 없습니다 · 목록에 있는 장르만 선택할 수 있습니다
        </p>
      )}

      {isOpen && !hasNoMatch && (
        <div
          id={listboxId}
          className="absolute inset-x-0 top-full z-10 mt-sb-1 max-h-96 overflow-y-auto rounded-sb-control border border-sb-hairline-strong bg-sb-canvas py-sb-1 shadow-sb-popover"
        >
          <p className="px-sb-3 py-sb-2 text-sb-ink-mute">
            {trimmedQuery
              ? `‘${trimmedQuery}’ 와(과) 일치하는 장르 ${matches.length}개`
              : `선택할 수 있는 장르 ${matches.length}개`}
          </p>
          <ul role="listbox" aria-label="장르 목록">
            {matches.map((genre, index) => (
              <li
                key={genre.id}
                id={optionId(index)}
                role="option"
                aria-selected={index === active}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => addGenre(genre)}
                className={`flex h-9 cursor-pointer items-center gap-sb-2 px-sb-3 hover:bg-sb-canvas-active ${index === active ? "bg-sb-canvas-active" : ""}`}
              >
                <span className="flex-1">
                  <HighlightedName name={genre.name} query={trimmedQuery} />
                </span>
                {index === active && (
                  <kbd className="rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-surface px-sb-1 font-sb-sans text-sb-caption text-sb-ink-mute">
                    Enter
                  </kbd>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
