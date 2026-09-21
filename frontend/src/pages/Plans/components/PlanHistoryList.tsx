import LoadMoreSentinel from "@/components/LoadMoreSentinel"
import { formatSeoulDateTime } from "@/lib/seoulDate"
import type { PatchPlanHistoryItem } from "@/types"

const badgeClass = "rounded-sb-tag px-sb-2 text-sb-caption"

interface PlanHistoryListProps {
  items: PatchPlanHistoryItem[]
  totalCount: number
  selectedPlanId: number | null
  onSelect: (planId: number) => void
  hasNextPage: boolean
  isFetchingNextPage: boolean
  onLoadMore: () => void
}

export default function PlanHistoryList({
  items,
  totalCount,
  selectedPlanId,
  onSelect,
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
}: PlanHistoryListProps) {
  return (
    <div className="overflow-hidden rounded-sb-card border border-sb-hairline bg-sb-canvas-surface lg:sticky lg:top-sb-4 lg:max-h-[calc(100vh-9rem)] lg:overflow-y-auto">
      <div className="flex items-center justify-between border-b border-sb-hairline px-sb-5 py-sb-3">
        <h2 className="flex items-center gap-sb-2 font-medium">
          입력 내역
          <span className="font-sb-mono text-sb-ink-mute tabular-nums">
            {totalCount.toLocaleString("en-US")}
          </span>
        </h2>
        <p className="text-sb-caption text-sb-ink-mute">최신순</p>
      </div>
      <ul>
        {items.map((item) => {
          const selected = item.planId === selectedPlanId
          return (
            <li key={item.planId} className="border-b border-sb-hairline last:border-b-0">
              <button
                type="button"
                aria-current={selected ? "true" : undefined}
                onClick={() => onSelect(item.planId)}
                className={`flex w-full cursor-pointer flex-col items-start gap-sb-2 border-l-[3px] px-sb-5 py-sb-4 text-left focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-sb-primary ${
                  selected
                    ? "border-l-sb-primary bg-sb-canvas-active"
                    : "border-l-transparent hover:bg-sb-canvas-soft"
                }`}
              >
                <div className="flex w-full flex-wrap items-center justify-between gap-sb-2">
                  <span className="font-sb-mono text-sb-caption text-sb-ink-mute tabular-nums">
                    {formatSeoulDateTime(item.createdAt)}
                  </span>
                  <span className="flex items-center gap-sb-1">
                    {item.unknownEntityCount > 0 && (
                      <span className={`${badgeClass} bg-sb-tint-amber text-sb-amber-text`}>
                        미확인{" "}
                        <span className="font-sb-mono tabular-nums">{item.unknownEntityCount}</span>
                      </span>
                    )}
                    <span className={`${badgeClass} bg-sb-tint-primary text-sb-primary-text`}>
                      변경점 <span className="font-sb-mono tabular-nums">{item.slotCount}</span>
                    </span>
                  </span>
                </div>
                <span className="text-sb-caption font-medium text-sb-primary-text">
                  {item.gameTitle}
                </span>
                <span className="line-clamp-2 text-sb-caption text-sb-ink-mute">
                  {item.rawTextPreview}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      {hasNextPage && (
        <LoadMoreSentinel
          isLoading={isFetchingNextPage}
          onReach={onLoadMore}
          loadingLabel="이전 기획안 내역을 불러오는 중…"
        />
      )}
    </div>
  )
}
