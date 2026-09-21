import { Link, useSearchParams } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { usePatchPlanList } from "@/hooks/queries/patchQueries"
import { paths } from "@/router/paths"
import PlanDetailPanel from "./components/PlanDetailPanel"
import PlanHistoryList from "./components/PlanHistoryList"

const PLAN_PARAM = "planId"

const panelClass = "rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
const primaryLinkClass =
  "inline-flex h-sb-control shrink-0 items-center gap-sb-2 rounded-sb-control bg-sb-primary px-sb-4 font-medium text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

function PlanDocumentIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-6"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
      <path d="M14 2v5h5" />
      <path d="M9 13h6" />
      <path d="M9 17h4" />
    </svg>
  )
}

/** 저장된 기획안 내역을 최신순 목록과 선택한 내역의 상세로 함께 보여 준다. */
export default function PlansPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const plans = usePatchPlanList()

  const items = plans.data?.pages.flatMap((page) => page.items) ?? []
  const totalCount = plans.data?.pages[0]?.page.totalCount ?? items.length
  const requestedPlanId = Number(searchParams.get(PLAN_PARAM))
  const selectedPlanId = items.some((item) => item.planId === requestedPlanId)
    ? requestedPlanId
    : (items[0]?.planId ?? null)

  const selectPlan = (planId: number) => {
    const next = new URLSearchParams(searchParams)
    next.set(PLAN_PARAM, String(planId))
    setSearchParams(next, { replace: true })
  }

  return (
    <main className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <div className="flex flex-wrap items-center justify-between gap-sb-4">
        <div className="flex flex-col gap-sb-2">
          <h1 className="text-sb-section font-medium">기획안 내역</h1>
          <p className="text-sb-ink-mute">
            내 게임에 입력한 기획안과 구조화된 변경점을 최신순으로 다시 확인합니다.
          </p>
        </div>
        <Link to={paths.myGames} className={primaryLinkClass}>
          새 기획안 입력
        </Link>
      </div>

      {plans.isPending && (
        <div role="status" aria-live="polite" className={`${panelClass} p-sb-6 text-sb-ink-mute`}>
          기획안 내역을 불러오는 중입니다…
        </div>
      )}

      {plans.isError && (
        <div role="alert" className={`${panelClass} flex flex-col items-start gap-sb-3 p-sb-6`}>
          <p>
            {isApiError(plans.error)
              ? plans.error.message
              : "기획안 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
          </p>
          <Button variant="secondary" onClick={() => plans.refetch()} disabled={plans.isFetching}>
            다시 시도
          </Button>
        </div>
      )}

      {plans.isSuccess && items.length === 0 && (
        <div
          role="status"
          className={`${panelClass} flex flex-col items-center gap-sb-5 px-sb-12 py-sb-12 text-center`}
        >
          <span className="flex size-14 items-center justify-center rounded-full bg-sb-tint-primary text-sb-primary-text">
            <PlanDocumentIcon />
          </span>
          <div className="flex flex-col gap-sb-2">
            <p className="text-sb-title font-medium">아직 입력한 기획안이 없습니다</p>
            <p className="text-sb-ink-mute">
              게임 상세의 기획안 입력에서 기획안을 작성하면 구조화된 변경점과 함께 이곳에 쌓입니다.
            </p>
          </div>
          <Link to={paths.myGames} className={primaryLinkClass}>
            기획안 입력
          </Link>
        </div>
      )}

      {plans.isSuccess && items.length > 0 && (
        <div className="flex flex-col items-start gap-sb-6 lg:flex-row">
          <div className="w-full lg:w-[380px] lg:shrink-0">
            <PlanHistoryList
              items={items}
              totalCount={totalCount}
              selectedPlanId={selectedPlanId}
              onSelect={selectPlan}
              hasNextPage={plans.hasNextPage}
              isFetchingNextPage={plans.isFetchingNextPage}
              onLoadMore={() => {
                if (!plans.isFetchingNextPage) plans.fetchNextPage()
              }}
            />
          </div>
          <div className="w-full min-w-0 flex-1">
            {selectedPlanId !== null && (
              <PlanDetailPanel key={selectedPlanId} planId={selectedPlanId} />
            )}
          </div>
        </div>
      )}
    </main>
  )
}
