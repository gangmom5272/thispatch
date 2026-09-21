import { useState, type FormEvent } from "react"
import { useSearchParams } from "react-router"
import { isApiError } from "../../api/error"
import LoadMoreSentinel from "../../components/LoadMoreSentinel"
import {
  DEFAULT_GAME_FILTER,
  DEFAULT_GAME_SORT,
  formatGameRange,
  GAME_RANGE_FILTERS,
  GAME_SORT_OPTIONS,
  isGameSort,
  type GameRangeFilterGroup,
  type GameRangeKey,
} from "../../constants/games"
import { useGameList } from "../../hooks/queries/gameQueries"
import { useGenreList } from "../../hooks/queries/genreQueries"
import type { Game, GameFilterConditions } from "../../types"
import GameCard from "./components/GameCard"
import GameFilterDialog from "./components/GameFilterDialog"
import GameSearchCombobox from "./components/GameSearchCombobox"

const SKELETON_COUNT = 4

interface AppliedConditions extends GameFilterConditions {
  search: string
}

function readRange(params: URLSearchParams, key: GameRangeKey, min: number, max: number) {
  const raw = params.get(key)
  if (raw === null || !/^\d+$/.test(raw)) return undefined
  const value = Number(raw)
  return value >= min && value <= max ? value : undefined
}

function readConditions(params: URLSearchParams): AppliedConditions {
  const sort = params.get("sort")
  const genreIds = (params.get("genreIds") ?? "")
    .split(",")
    .map(Number)
    .filter((id) => Number.isInteger(id) && id > 0)
  const conditions: AppliedConditions = {
    search: params.get("search") ?? "",
    sort: isGameSort(sort) ? sort : DEFAULT_GAME_SORT,
    genreIds,
    developer: params.get("developer")?.trim() || undefined,
  }
  for (const group of GAME_RANGE_FILTERS) {
    const from = readRange(params, group.from, group.min, group.max)
    const to = readRange(params, group.to, group.min, group.max)
    if (from !== undefined && to !== undefined && from > to) continue
    conditions[group.from] = from
    conditions[group.to] = to
  }
  return conditions
}

function writeConditions({
  search,
  sort,
  genreIds,
  developer,
  ...ranges
}: AppliedConditions): URLSearchParams {
  const params = new URLSearchParams()
  if (search) params.set("search", search)
  if (sort !== DEFAULT_GAME_SORT) params.set("sort", sort)
  if (genreIds.length > 0) params.set("genreIds", genreIds.join(","))
  if (developer) params.set("developer", developer)
  for (const [key, value] of Object.entries(ranges)) {
    if (value !== undefined) params.set(key, String(value))
  }
  return params
}

const primaryButtonClass =
  "h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-primary-deep disabled:cursor-not-allowed disabled:opacity-50"
const secondaryButtonClass =
  "flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
const chipClass =
  "flex h-7 items-center gap-sb-1 rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2"
const removableChipClass = `${chipClass} cursor-pointer hover:border-sb-hairline-strong focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary`
const panelClass =
  "flex flex-col items-start gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6"

export default function GameListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const applied = readConditions(searchParams)
  const [isFilterOpen, setIsFilterOpen] = useState(false)

  const filters = {
    ...applied,
    search: applied.search || undefined,
    genreIds: applied.genreIds.length > 0 ? applied.genreIds : undefined,
  }
  const query = useGameList(filters)

  /** URL에 검색·필터 조건을 반영한다. 조건이 바뀌면 쿼리 키가 바뀌어 첫 페이지부터 다시 조회한다. */
  const apply = (next: AppliedConditions) => {
    setSearchParams(writeConditions(next))
  }

  const rangeChips = GAME_RANGE_FILTERS.flatMap((group) => {
    const range = formatGameRange(group, applied[group.from], applied[group.to])
    return range ? [{ group, range }] : []
  })
  const appliedFilterCount =
    applied.genreIds.length +
    rangeChips.length +
    (applied.developer ? 1 : 0) +
    (applied.sort !== DEFAULT_GAME_SORT ? 1 : 0)
  const hasAppliedConditions = appliedFilterCount > 0
  const totalCount = query.data?.pages[0]?.page.totalCount

  return (
    <div className="min-h-screen bg-sb-canvas-base font-sb-sans text-sb-body text-sb-ink scheme-dark">
      <main className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
        <h1 className="text-sb-section font-medium md:text-sb-display">게임 탐색</h1>

        {/* URL이 바뀌면(로고 홈 등) 검색 입력·필터 배지를 초기 상태로 리마운트 */}
        <GameListToolbar
          key={searchParams.toString()}
          applied={applied}
          rangeChips={rangeChips}
          appliedFilterCount={appliedFilterCount}
          hasAppliedConditions={hasAppliedConditions}
          totalCount={totalCount}
          onApply={apply}
          onOpenFilter={() => setIsFilterOpen(true)}
        />

        <section className="flex flex-col gap-sb-5">
          <SectionLabel count={totalCount}>전체 게임</SectionLabel>
          {query.isPending && <GameGridSkeleton />}
          {query.isError && (
            <div role="alert" className={panelClass}>
              <p>
                {isApiError(query.error)
                  ? query.error.message
                  : "게임 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
              </p>
              <button
                type="button"
                onClick={() => query.refetch()}
                disabled={query.isFetching}
                className={secondaryButtonClass}
              >
                다시 시도
              </button>
            </div>
          )}
          {query.isSuccess && (
            <AllGames
              items={query.data.pages.flatMap((page) => page.items)}
              search={applied.search}
              hasFilters={hasAppliedConditions}
              hasNextPage={query.hasNextPage}
              isFetchingNextPage={query.isFetchingNextPage}
              isFetchNextPageError={query.isFetchNextPageError}
              onLoadMore={() => query.fetchNextPage()}
              onResetConditions={() => {
                apply({ ...DEFAULT_GAME_FILTER, search: "" })
              }}
            />
          )}
        </section>
      </main>

      {isFilterOpen && (
        <GameFilterDialog
          initial={applied}
          onApply={(next) => {
            // 필터 적용 시 URL·목록을 즉시 갱신(검색어는 현재 적용값 유지)
            apply({ ...next, search: applied.search })
          }}
          onClose={() => setIsFilterOpen(false)}
        />
      )}
    </div>
  )
}

interface RangeChip {
  group: GameRangeFilterGroup
  range: string
}

interface GameListToolbarProps {
  applied: AppliedConditions
  rangeChips: RangeChip[]
  appliedFilterCount: number
  hasAppliedConditions: boolean
  totalCount: number | undefined
  onApply: (next: AppliedConditions) => void
  onOpenFilter: () => void
}

/** 검색·필터 툴바. key로 리마운트되면 URL의 적용 조건으로 입력을 다시 맞춘다. */
function GameListToolbar({
  applied,
  rangeChips,
  appliedFilterCount,
  hasAppliedConditions,
  totalCount,
  onApply,
  onOpenFilter,
}: GameListToolbarProps) {
  const [searchValue, setSearchValue] = useState(applied.search)
  const genres = useGenreList().data ?? []
  const genreName = (id: number) => genres.find((genre) => genre.id === id)?.name ?? `장르 ${id}`
  const sortLabel = GAME_SORT_OPTIONS.find((option) => option.value === applied.sort)?.label

  const searchWith = (term: string) =>
    onApply({ sort: applied.sort, genreIds: applied.genreIds, search: term.trim() })

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    searchWith(searchValue)
  }

  return (
    <form role="search" onSubmit={handleSearch} className="flex flex-wrap items-center gap-sb-3">
      <GameSearchCombobox value={searchValue} onChange={setSearchValue} onSearch={searchWith} />
      <button type="submit" className={primaryButtonClass}>
        검색
      </button>
      <button
        type="button"
        onClick={onOpenFilter}
        className={`${secondaryButtonClass} ${appliedFilterCount > 0 ? "bg-sb-canvas-active" : ""}`}
      >
        필터
        {appliedFilterCount > 0 ? (
          <span className="rounded-full bg-sb-primary px-sb-2 text-sb-caption text-sb-on-primary tabular-nums">
            {appliedFilterCount}
          </span>
        ) : null}
      </button>

      {hasAppliedConditions ? (
        <ul aria-label="적용된 조건" className="flex flex-wrap items-center gap-sb-2">
          {applied.genreIds.map((id) => {
            const name = genreName(id)
            return (
              <li key={id}>
                <button
                  type="button"
                  aria-label={`${name} 장르 조건 제거`}
                  onClick={() =>
                    onApply({
                      ...applied,
                      genreIds: applied.genreIds.filter((genreId) => genreId !== id),
                    })
                  }
                  className={removableChipClass}
                >
                  {name}
                  <span aria-hidden="true" className="text-sb-ink-mute">
                    ×
                  </span>
                </button>
              </li>
            )
          })}
          {rangeChips.map(({ group, range }) => (
            <li key={group.from}>
              <button
                type="button"
                aria-label={`${group.label} ${range} 조건 제거`}
                onClick={() =>
                  onApply({ ...applied, [group.from]: undefined, [group.to]: undefined })
                }
                className={removableChipClass}
              >
                {group.label}
                <span className="font-sb-mono tabular-nums">{range}</span>
                <span aria-hidden="true" className="text-sb-ink-mute">
                  ×
                </span>
              </button>
            </li>
          ))}
          {applied.developer ? (
            <li>
              <button
                type="button"
                aria-label={`개발사 ${applied.developer} 조건 제거`}
                onClick={() => onApply({ ...applied, developer: undefined })}
                className={removableChipClass}
              >
                개발사 {applied.developer}
                <span aria-hidden="true" className="text-sb-ink-mute">
                  ×
                </span>
              </button>
            </li>
          ) : null}
          <li className={chipClass}>
            <span aria-hidden="true" className="text-sb-ink-mute">
              ⇅
            </span>
            {sortLabel}
          </li>
          <li>
            <button
              type="button"
              onClick={() => onApply({ ...DEFAULT_GAME_FILTER, search: applied.search })}
              className="cursor-pointer rounded-sb-control text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
            >
              초기화
            </button>
          </li>
        </ul>
      ) : (
        <p className="text-sb-ink-mute">장르 전체 · {sortLabel}</p>
      )}

      {totalCount !== undefined ? (
        <p className="ml-auto text-sb-ink-mute tabular-nums" aria-live="polite">
          {totalCount.toLocaleString("en-US")}개 게임
        </p>
      ) : null}
    </form>
  )
}

function SectionLabel({ children, count }: { children: string; count?: number }) {
  return (
    <h2 className="flex items-center gap-sb-3 text-sb-heading font-medium text-sb-ink">
      {children}
      {count !== undefined && (
        <span className="rounded-sb-tag bg-sb-tint-primary px-sb-2 font-sb-mono text-sb-caption text-sb-primary-text tabular-nums">
          {count.toLocaleString("en-US")}
        </span>
      )}
      <span aria-hidden="true" className="h-px flex-1 bg-sb-hairline" />
    </h2>
  )
}

const cardSkeletonClass =
  "h-72 animate-pulse rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface motion-reduce:animate-none"

function GameGridSkeleton() {
  return (
    <ul
      aria-label="게임 목록 불러오는 중"
      aria-busy="true"
      className="grid grid-cols-1 gap-sb-5 sm:grid-cols-2 lg:grid-cols-4"
    >
      {Array.from({ length: SKELETON_COUNT }, (_, index) => (
        <li key={index} className={cardSkeletonClass} />
      ))}
    </ul>
  )
}
interface AllGamesProps {
  items: Game[]
  /** 현재 적용된 검색어 — 빈 상태 안내에 표시 */
  search: string
  hasFilters: boolean
  hasNextPage: boolean
  isFetchingNextPage: boolean
  isFetchNextPageError: boolean
  onLoadMore: () => void
  onResetConditions: () => void
}

function AllGames({
  items,
  search,
  hasFilters,
  hasNextPage,
  isFetchingNextPage,
  isFetchNextPageError,
  onLoadMore,
  onResetConditions,
}: AllGamesProps) {
  if (items.length === 0) {
    return (
      <div className={panelClass} role="status">
        <p className="text-sb-title font-medium text-sb-ink">검색 결과가 없습니다</p>
        {search ? (
          <p className="text-sb-ink-mute">
            &ldquo;{search}&rdquo;에 맞는 게임을 찾지 못했습니다. 다른 검색어를 입력해 보세요.
          </p>
        ) : (
          <p className="text-sb-ink-mute">
            조건에 맞는 게임이 없습니다. 필터를 줄이거나 검색어를 바꿔 다시 시도해 주세요.
          </p>
        )}
        {(search || hasFilters) && (
          <button type="button" onClick={onResetConditions} className={secondaryButtonClass}>
            검색 조건 초기화
          </button>
        )}
      </div>
    )
  }

  return (
    <>
      <ul
        aria-label="전체 게임"
        className="grid grid-cols-1 gap-sb-5 sm:grid-cols-2 lg:grid-cols-4"
      >
        {items.map((game) => (
          <li key={game.id} className="h-full">
            <GameCard game={game} isMine={game.isMine} />
          </li>
        ))}
      </ul>
      {isFetchNextPageError && (
        <div className="flex flex-col items-center gap-sb-2 py-sb-4">
          <p role="alert" className="text-sb-neg-text">
            다음 게임을 불러오지 못했습니다. 다시 시도해 주세요.
          </p>
          <button type="button" onClick={onLoadMore} className={secondaryButtonClass}>
            다시 시도
          </button>
        </div>
      )}
      {hasNextPage && !isFetchNextPageError && (
        <LoadMoreSentinel
          key={items.length}
          isLoading={isFetchingNextPage}
          onReach={() => {
            if (!isFetchingNextPage) onLoadMore()
          }}
        />
      )}
    </>
  )
}
