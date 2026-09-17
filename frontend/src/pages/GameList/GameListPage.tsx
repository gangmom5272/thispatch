import { useState, type FormEvent } from "react"
import { useSearchParams } from "react-router"
import { isApiError } from "../../api/error"
import LoadMoreSentinel from "../../components/LoadMoreSentinel"
import {
  DEFAULT_GAME_FILTER,
  DEFAULT_GAME_SORT,
  GAME_SORT_OPTIONS,
  isGameSort,
} from "../../constants/games"
import { useDragScroll } from "../../hooks/useDragScroll"
import { useGameList, useMyGameList } from "../../hooks/queries/gameQueries"
import { useGenreList } from "../../hooks/queries/genreQueries"
import type { Game, GameFilterConditions, MyGame } from "../../types"
import GameCard from "./components/GameCard"
import GameFilterDialog from "./components/GameFilterDialog"
import GameSearchCombobox from "./components/GameSearchCombobox"

const SKELETON_COUNT = 4

interface AppliedConditions extends GameFilterConditions {
  search: string
}

function readConditions(params: URLSearchParams): AppliedConditions {
  const sort = params.get("sort")
  const genreIds = (params.get("genreIds") ?? "")
    .split(",")
    .map(Number)
    .filter((id) => Number.isInteger(id) && id > 0)
  return {
    search: params.get("search") ?? "",
    sort: isGameSort(sort) ? sort : DEFAULT_GAME_SORT,
    genreIds,
  }
}

function writeConditions({ search, sort, genreIds }: AppliedConditions): URLSearchParams {
  const params = new URLSearchParams()
  if (search) params.set("search", search)
  if (sort !== DEFAULT_GAME_SORT) params.set("sort", sort)
  if (genreIds.length > 0) params.set("genreIds", genreIds.join(","))
  return params
}

const primaryButtonClass =
  "h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-primary-deep disabled:cursor-not-allowed disabled:opacity-50"
const secondaryButtonClass =
  "flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
const chipClass =
  "flex h-7 items-center gap-sb-1 rounded-sb-tag border border-sb-hairline-cool bg-sb-canvas-soft px-sb-2"
const panelClass =
  "flex flex-col items-start gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6"

export default function GameListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const applied = readConditions(searchParams)
  const [pending, setPending] = useState<GameFilterConditions>(() => ({
    sort: applied.sort,
    genreIds: applied.genreIds,
  }))
  const [isFilterOpen, setIsFilterOpen] = useState(false)
  const [searchValue, setSearchValue] = useState(applied.search)

  const filters = {
    search: applied.search || undefined,
    sort: applied.sort,
    genreIds: applied.genreIds.length > 0 ? applied.genreIds : undefined,
  }
  const query = useGameList(filters)
  const myGames = useMyGameList(filters)

  const apply = (next: AppliedConditions) => {
    setPending({ sort: next.sort, genreIds: next.genreIds })
    setSearchParams(writeConditions(next))
  }

  const searchWith = (term: string) => apply({ ...pending, search: term.trim() })

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    searchWith(searchValue)
  }

  const genres = useGenreList().data ?? []
  const genreName = (id: number) => genres.find((genre) => genre.id === id)?.name ?? `장르 ${id}`

  const sortLabel = GAME_SORT_OPTIONS.find((option) => option.value === applied.sort)?.label
  const hasAppliedConditions = applied.genreIds.length > 0 || applied.sort !== DEFAULT_GAME_SORT
  const pendingCount = pending.genreIds.length + (pending.sort !== DEFAULT_GAME_SORT ? 1 : 0)
  const totalCount = query.data?.pages[0]?.page.totalCount

  return (
    <div className="min-h-screen bg-sb-canvas-base font-sb-sans text-sb-body text-sb-ink scheme-dark">
      <main className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
        <h1 className="text-sb-section font-medium md:text-sb-display">게임 목록</h1>

        <form
          role="search"
          onSubmit={handleSearch}
          className="flex flex-wrap items-center gap-sb-3"
        >
          <GameSearchCombobox value={searchValue} onChange={setSearchValue} onSearch={searchWith} />
          <button type="submit" className={primaryButtonClass}>
            검색
          </button>
          <button
            type="button"
            onClick={() => setIsFilterOpen(true)}
            className={`${secondaryButtonClass} ${pendingCount > 0 ? "bg-sb-canvas-active" : ""}`}
          >
            필터
            {pendingCount > 0 && (
              <span className="rounded-full bg-sb-primary px-sb-2 text-sb-caption text-sb-on-primary tabular-nums">
                {pendingCount}
              </span>
            )}
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
                        apply({
                          ...applied,
                          genreIds: applied.genreIds.filter((genreId) => genreId !== id),
                        })
                      }
                      className={`${chipClass} cursor-pointer hover:border-sb-hairline-strong focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary`}
                    >
                      {name}
                      <span aria-hidden="true" className="text-sb-ink-mute">
                        ×
                      </span>
                    </button>
                  </li>
                )
              })}
              <li className={chipClass}>
                <span aria-hidden="true" className="text-sb-ink-mute">
                  ⇅
                </span>
                {sortLabel}
              </li>
              <li>
                <button
                  type="button"
                  onClick={() => apply({ ...DEFAULT_GAME_FILTER, search: applied.search })}
                  className="cursor-pointer rounded-sb-control text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
                >
                  초기화
                </button>
              </li>
            </ul>
          ) : (
            <p className="text-sb-ink-mute">장르 전체 · {sortLabel}</p>
          )}

          {totalCount !== undefined && (
            <p className="ml-auto text-sb-ink-mute tabular-nums" aria-live="polite">
              {totalCount}개 게임
            </p>
          )}
        </form>

        <MyGameSection
          items={myGames.data ?? []}
          isPending={myGames.isPending}
          isError={myGames.isError}
          error={myGames.error}
          isFetching={myGames.isFetching}
          onRetry={() => myGames.refetch()}
        />

        <section className="flex flex-col gap-sb-5">
          <SectionLabel>전체 게임</SectionLabel>
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
                setSearchValue("")
                apply({ search: "", sort: DEFAULT_GAME_SORT, genreIds: [] })
              }}
            />
          )}
        </section>
      </main>

      {isFilterOpen && (
        <GameFilterDialog
          initial={pending}
          onApply={setPending}
          onClose={() => setIsFilterOpen(false)}
        />
      )}
    </div>
  )
}

function SectionLabel({ children }: { children: string }) {
  return (
    <h2 className="flex items-center gap-sb-3 text-sb-heading text-sb-ink-mute">
      {children}
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

interface MyGameSectionProps {
  items: MyGame[]
  isPending: boolean
  isError: boolean
  error: unknown
  isFetching: boolean
  onRetry: () => void
}

const railClass =
  "flex gap-sb-5 overflow-x-auto pb-sb-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"

function MyGameSection({
  items,
  isPending,
  isError,
  error,
  isFetching,
  onRetry,
}: MyGameSectionProps) {
  const { setRef, dragProps } = useDragScroll()

  if (!isPending && !isError && items.length === 0) return null

  return (
    <section className="flex flex-col gap-sb-5">
      <SectionLabel>내 게임</SectionLabel>
      {isPending && (
        <ul aria-label="내 게임 불러오는 중" aria-busy="true" className={railClass}>
          {Array.from({ length: SKELETON_COUNT }, (_, index) => (
            <li key={index} className={`w-72 shrink-0 ${cardSkeletonClass}`} />
          ))}
        </ul>
      )}
      {isError && (
        <div role="alert" className={panelClass}>
          <p>
            {isApiError(error)
              ? error.message
              : "내 게임을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
          </p>
          <button
            type="button"
            onClick={onRetry}
            disabled={isFetching}
            className={secondaryButtonClass}
          >
            다시 시도
          </button>
        </div>
      )}
      {items.length > 0 && (
        <ul
          ref={setRef}
          tabIndex={0}
          aria-label="내 게임"
          onPointerDown={dragProps.onPointerDown}
          onClickCapture={dragProps.onClickCapture}
          onDragStartCapture={dragProps.onDragStartCapture}
          style={dragProps.style}
          className={`${railClass} ${dragProps.className} focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary`}
        >
          {items.map((game) => (
            <li key={game.id} className="w-72 shrink-0">
              <GameCard game={game} isMine />
            </li>
          ))}
        </ul>
      )}
    </section>
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
