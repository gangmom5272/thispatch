import { useId } from "react"
import { Link, useLocation, useParams, useSearchParams } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { GameHeader } from "@/components/GameHeader"
import PlanSteps from "@/components/PlanSteps"
import ScrollToTopButton from "@/components/ScrollToTopButton"
import { useCaseSearch } from "@/hooks/queries/patchQueries"
import NotFound from "@/pages/NotFound"
import { GAME_DETAIL_TABS, gameDetailTabPath, paths } from "@/router/paths"
import type { CaseSearchInput, CaseSearchSort, ConfirmedSlot, PlanSlot } from "@/types"
import OutcomeColumn from "./components/OutcomeColumn"

const SORT_OPTIONS: Array<{ value: CaseSearchSort; label: string }> = [
  { value: "SIMILARITY_DESC", label: "유사도 높은 순" },
  { value: "REVIEW_COUNT_DESC", label: "리뷰 많은 순" },
  { value: "PATCHED_ON_DESC", label: "최신순" },
]
const DEFAULT_SORT: CaseSearchSort = "SIMILARITY_DESC"

const panelClass = "rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
const linkClass =
  "rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

interface SearchState {
  planId: number
  slots: PlanSlot[]
  genreIds: number[]
}

function isSearchState(value: unknown): value is SearchState {
  if (!value || typeof value !== "object") return false
  const state = value as Partial<SearchState>
  return (
    typeof state.planId === "number" &&
    state.planId > 0 &&
    Array.isArray(state.slots) &&
    state.slots.length > 0 &&
    Array.isArray(state.genreIds)
  )
}

function toConfirmedSlot(slot: PlanSlot): ConfirmedSlot {
  return {
    target: { name: slot.targetName, role: slot.targetRole },
    attribute: slot.attribute,
    changeType: slot.changeType,
    magnitude: slot.magnitude,
    direction: slot.direction,
    scope: slot.scope,
  }
}

function isSort(value: string | null): value is CaseSearchSort {
  return SORT_OPTIONS.some((option) => option.value === value)
}

function unique(values: Array<string | null>): string {
  const set = [...new Set(values.filter((value) => value !== null && value.trim() !== ""))]
  return set.length > 0 ? set.join("·") : "미확인"
}

export default function CaseSearchPage() {
  const { gameId } = useParams()
  if (!/^\d+$/.test(gameId ?? "")) {
    return <NotFound />
  }
  return <CaseSearchContent gameId={Number(gameId)} />
}

function CaseSearchContent({ gameId }: { gameId: number }) {
  const sortId = useId()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const state: SearchState | null = isSearchState(location.state) ? location.state : null
  const rawSort = searchParams.get("sort")
  const sort = isSort(rawSort) ? rawSort : DEFAULT_SORT

  const input: CaseSearchInput | null = state && {
    planId: state.planId,
    confirmedSlots: state.slots.map(toConfirmedSlot),
    genreIds: state.genreIds,
    sort,
  }
  const search = useCaseSearch(gameId, input)
  const planPath = gameDetailTabPath(gameId, GAME_DETAIL_TABS.plan)

  const axes = input
    ? [
        ["대상", unique(input.confirmedSlots.map((slot) => slot.target.role))],
        ["속성", unique(input.confirmedSlots.map((slot) => slot.attribute))],
        ["방향", unique(input.confirmedSlots.map((slot) => slot.direction))],
        ["범위", unique(input.confirmedSlots.map((slot) => slot.scope))],
      ]
    : []

  const changeSort = (value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value === DEFAULT_SORT) next.delete("sort")
    else next.set("sort", value)
    setSearchParams(next, { replace: true, state: location.state })
  }

  return (
    <>
      <GameHeader gameId={gameId} back="gameDetail" />
      <main className="mx-auto flex max-w-sb-page flex-col gap-sb-4 px-sb-4 py-sb-6 md:px-sb-12">
        <PlanSteps current={1} />

        {!input ? (
          <div className={`${panelClass} flex flex-col gap-sb-2 p-sb-6`}>
            <p>검색할 기획안이 없습니다.</p>
            <p className="text-sb-ink-mute">
              기획안을 구조화한 뒤 ‘유사 사례 검색’을 누르면 변경 슬롯을 기준으로 과거 패치를
              찾습니다.
            </p>
            <Link to={planPath} className={linkClass}>
              기획안 입력으로 이동
            </Link>
          </div>
        ) : (
          <>
            <div className={`${panelClass} flex flex-wrap items-center gap-sb-2 px-sb-4 py-sb-3`}>
              <span aria-hidden="true" className="text-sb-primary">
                ⌕
              </span>
              <dl className="flex flex-wrap items-center gap-sb-2">
                {axes.map(([key, value]) => (
                  <div
                    key={key}
                    className="flex items-center gap-sb-1 rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 py-sb-1"
                  >
                    <dt className="text-sb-ink-mute">{key}</dt>
                    <dd className="font-medium">{value}</dd>
                  </div>
                ))}
              </dl>
              {search.isSuccess && (
                <span className="font-sb-mono text-sb-ink-mute tabular-nums">
                  사례 {search.data.totalCount.toLocaleString("en-US")}건
                </span>
              )}
              <div className="flex items-center gap-sb-2 sm:ml-auto">
                <label htmlFor={sortId} className="text-sb-ink-mute">
                  정렬
                </label>
                <select
                  id={sortId}
                  value={sort}
                  disabled={search.isFetching}
                  onChange={(event) => changeSort(event.target.value)}
                  className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-cool bg-sb-canvas px-sb-3 text-sb-body text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {SORT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <p className="text-sb-ink-mute">
              같은 축의 과거 패치를 부정 급변 · 변화 없음 · 긍정 급변 세 결과군으로 나눠 제시합니다.
            </p>

            {search.isPending && (
              <p
                role="status"
                aria-live="polite"
                className={`${panelClass} p-sb-6 text-sb-ink-mute`}
              >
                유사 사례를 검색하는 중입니다…
              </p>
            )}

            {search.isError && (
              <div role="alert" className={`${panelClass} flex flex-col gap-sb-3 p-sb-6`}>
                <p>
                  {isApiError(search.error)
                    ? search.error.message
                    : "유사 사례를 검색하지 못했습니다. 잠시 후 다시 시도해 주세요."}
                </p>
                <div className="flex flex-wrap items-center gap-sb-3">
                  {isApiError(search.error) && search.error.status === 401 ? (
                    <Link to={paths.login} className={linkClass}>
                      로그인하기
                    </Link>
                  ) : (
                    <Button
                      variant="secondary"
                      onClick={() => search.refetch()}
                      disabled={search.isFetching}
                    >
                      다시 시도
                    </Button>
                  )}
                  <Link to={planPath} className={linkClass}>
                    기획안으로 돌아가기
                  </Link>
                </div>
              </div>
            )}

            {search.isSuccess && search.data.totalCount === 0 && (
              <div className={`${panelClass} flex flex-col gap-sb-4 p-sb-8`}>
                <h2 className="text-sb-section font-medium">현재 조건과 맞는 사례가 없습니다</h2>
                <p className="text-sb-lead text-sb-ink-mute">
                  기획안과 진단 맥락은 유지했습니다. 장르나 적용 범위를 넓혀 다시 검색하세요. 결과가
                  없다는 사실은 위험이나 안전의 근거가 아닙니다.
                </p>
                <p className="text-sb-lead">{axes.map(([, value]) => value).join(" / ")}</p>
                <div className="flex flex-wrap items-center gap-sb-5">
                  <Link
                    to={planPath}
                    className="inline-flex h-sb-control items-center rounded-sb-control bg-sb-primary px-sb-4 font-medium text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-primary-deep"
                  >
                    검색 조건 수정
                  </Link>
                  <Link to={planPath} className={linkClass}>
                    기획안으로 돌아가기
                  </Link>
                </div>
              </div>
            )}

            {search.isSuccess && search.data.totalCount > 0 && (
              <>
                {search.data.notices.length > 0 && (
                  <ul
                    aria-label="해석 주의"
                    className="flex flex-col gap-sb-1 rounded-sb-control border border-sb-hairline bg-sb-canvas-night-soft px-sb-4 py-sb-3"
                  >
                    {search.data.notices.map((notice) => (
                      <li key={notice} className="flex gap-sb-2">
                        <span aria-hidden="true" className="text-sb-ink-mute">
                          ⓘ
                        </span>
                        {notice}
                      </li>
                    ))}
                  </ul>
                )}
                <div className="grid grid-cols-1 gap-sb-5 lg:grid-cols-3 lg:gap-y-sb-3">
                  {search.data.groups.map((group) => (
                    <OutcomeColumn key={group.outcome} group={group} gameId={gameId} />
                  ))}
                </div>
              </>
            )}
          </>
        )}
      </main>
      <ScrollToTopButton />
    </>
  )
}
