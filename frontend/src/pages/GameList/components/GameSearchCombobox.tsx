import { useId, useState, type KeyboardEvent } from "react"
import { useGameSuggestions } from "../../../hooks/queries/gameQueries"
import { useDebouncedValue } from "../../../hooks/useDebouncedValue"
import type { Game } from "../../../types"

const MIN_SUGGEST_LENGTH = 2
const SUGGEST_DEBOUNCE_MS = 250
const SEARCH_MAX_LENGTH = 100

interface GameSearchComboboxProps {
  value: string
  onChange: (value: string) => void
  onSearch: (term: string) => void
}

function SearchIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-4 shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
    >
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  )
}

export default function GameSearchCombobox({ value, onChange, onSearch }: GameSearchComboboxProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)
  const listboxId = useId()

  const term = value.trim()
  const debouncedTerm = useDebouncedValue(term, SUGGEST_DEBOUNCE_MS)
  const canSuggest = debouncedTerm.length >= MIN_SUGGEST_LENGTH
  const suggestions = useGameSuggestions(debouncedTerm, canSuggest)
  const items = canSuggest ? (suggestions.data ?? []) : []
  const active = activeIndex < items.length ? activeIndex : -1
  const isListOpen = isOpen && term.length >= MIN_SUGGEST_LENGTH
  const optionId = (index: number) => `${listboxId}-option-${index}`

  const closeList = () => {
    setIsOpen(false)
    setActiveIndex(-1)
  }

  const search = (next: string) => {
    closeList()
    onSearch(next)
  }

  const selectSuggestion = (game: Game) => {
    onChange(game.title)
    search(game.title)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault()
        if (!isListOpen) {
          setIsOpen(true)
        } else if (items.length > 0) {
          setActiveIndex((active + 1) % items.length)
        }
        break
      case "ArrowUp":
        event.preventDefault()
        setActiveIndex(active <= 0 ? -1 : active - 1)
        break
      case "Enter": {
        event.preventDefault()
        const selected = items[active]
        if (selected) {
          selectSuggestion(selected)
        } else {
          search(term)
        }
        break
      }
      case "Escape":
        if (isListOpen) {
          event.preventDefault()
          closeList()
        }
        break
      case "Tab":
        closeList()
        break
    }
  }

  return (
    <div className="relative w-full sm:w-96">
      <label className="relative block">
        <span className="sr-only">게임 이름 검색</span>
        <span className="pointer-events-none absolute inset-y-0 left-sb-3 flex items-center text-sb-ink-mute">
          <SearchIcon />
        </span>
        <input
          type="text"
          name="search"
          role="combobox"
          autoComplete="off"
          aria-expanded={isListOpen}
          aria-controls={isListOpen ? listboxId : undefined}
          aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? optionId(active) : undefined}
          value={value}
          maxLength={SEARCH_MAX_LENGTH}
          placeholder="게임 이름 검색"
          onChange={(event) => {
            onChange(event.target.value)
            setIsOpen(true)
            setActiveIndex(-1)
          }}
          onFocus={() => setIsOpen(true)}
          onBlur={closeList}
          onKeyDown={handleKeyDown}
          className="h-sb-control w-full rounded-sb-control border border-sb-hairline-cool bg-sb-canvas-surface pr-sb-3 pl-sb-8 text-sb-ink placeholder:text-sb-ink-mute focus-visible:border-sb-primary focus-visible:outline-none"
        />
      </label>

      {isListOpen && (
        <div
          id={listboxId}
          className="absolute inset-x-0 top-full z-10 mt-sb-1 rounded-sb-control border border-sb-hairline-cool bg-sb-canvas py-sb-1 shadow-sb-popover"
        >
          {suggestions.isError && (
            <div role="status" aria-live="polite" className="text-sb-ink-mute">
              <p className="flex h-10 items-center gap-sb-2 px-sb-3">
                <SearchIcon />
                추천을 불러오지 못했습니다
              </p>
              <p className="flex h-10 items-center gap-sb-2 px-sb-3">
                <SearchIcon />
                입력한 값으로 바로 검색할 수 있습니다
              </p>
            </div>
          )}
          {!suggestions.isError && items.length === 0 && (
            <p
              role="status"
              aria-live="polite"
              className="flex h-10 items-center gap-sb-2 px-sb-3 text-sb-ink-mute"
            >
              <SearchIcon />
              {suggestions.isFetching || !canSuggest
                ? "추천을 불러오는 중…"
                : "추천 검색어가 없습니다"}
            </p>
          )}
          {!suggestions.isError && items.length > 0 && (
            <ul
              role="listbox"
              aria-label="추천 게임"
              className={suggestions.isFetching ? "opacity-50" : undefined}
            >
              {items.map((game, index) => (
                <li
                  key={game.id}
                  id={optionId(index)}
                  role="option"
                  aria-selected={index === active}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => selectSuggestion(game)}
                  className={`flex h-10 cursor-pointer items-center gap-sb-2 px-sb-3 hover:bg-sb-canvas-active ${index === active ? "bg-sb-canvas-active text-sb-primary" : "text-sb-ink-mute"}`}
                >
                  <SearchIcon />
                  <span className="flex-1 truncate text-sb-ink">{game.title}</span>
                  <span className="font-sb-mono text-sb-caption text-sb-ink-mute tabular-nums">
                    긍정률 {game.positiveRate}%
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
