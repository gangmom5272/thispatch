import { useParams } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { useLanguageAnalysis } from "@/hooks/queries/languageAnalysisQueries"
import { formatPercent } from "@/lib/format"
import type { LanguageAnalysis, LanguageShare } from "@/types"
import LanguageRow, { LANGUAGE_ROW_GRID } from "./components/LanguageRow"

const TOP_LANGUAGE_COUNT = 5

const panelClass = "rounded-sb-card bg-sb-canvas-surface"

function splitByShare(languages: LanguageShare[]): {
  top: LanguageShare[]
  rest: LanguageShare[]
} {
  const sorted = languages.toSorted((a, b) => b.reviewShare - a.reviewShare)
  return { top: sorted.slice(0, TOP_LANGUAGE_COUNT), rest: sorted.slice(TOP_LANGUAGE_COUNT) }
}

function formatCount(count: number): string {
  return `${count.toLocaleString("en-US")}건`
}

function PeriodBar({ data }: { data: LanguageAnalysis }) {
  const { period } = data.meta
  return (
    <div className={`flex flex-wrap items-center gap-sb-3 px-sb-4 py-sb-3 ${panelClass}`}>
      <span className="text-sb-ink-mute">데이터 구간</span>
      <span className="font-sb-mono font-medium text-sb-ink tabular-nums">
        {period.startDate} ~ {period.endDate}
      </span>
      <span className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-[3px] text-sb-ink-mute">
        오늘 기준 최근 {period.dayCount}일
      </span>
      <span aria-hidden="true" className="hidden h-[18px] w-px bg-sb-hairline-strong sm:block" />
      <span className="font-sb-mono font-medium text-sb-ink tabular-nums">
        리뷰 {formatCount(data.totalReviewCount)}
      </span>
      {data.isSufficientSample ? (
        <span className="rounded-sb-tag bg-sb-tint-green px-[10px] md:ml-auto py-sb-1 font-medium text-sb-pos-text tabular-nums">
          ✓ 표본 충족 · {formatCount(data.totalReviewCount)}
        </span>
      ) : (
        <span className="rounded-sb-tag bg-sb-tint-amber px-[10px] md:ml-auto py-sb-1 font-medium text-sb-amber-text tabular-nums">
          표본 부족 · {formatCount(data.totalReviewCount)} (최소{" "}
          {formatCount(data.minimumSampleCount)})
        </span>
      )}
    </div>
  )
}

function SampleNote({ data, excluded }: { data: LanguageAnalysis; excluded: LanguageShare[] }) {
  const { period } = data.meta
  const excludedText =
    excluded.length > 0
      ? ` 비중이 낮은 언어(${excluded
          .map((item) => `${item.languageCode} ${formatPercent(item.reviewShare)}`)
          .join(", ")})는 목록에서 제외했습니다.`
      : ""
  return (
    <p className="flex gap-sb-2 text-sb-ink-mute">
      <span aria-hidden="true">ⓘ</span>
      <span>
        분석 구간은 오늘 기준 최근 {period.dayCount}일({period.startDate} ~ {period.endDate}
        )입니다. 긍정률은 그 구간에 수정된 해당 언어 리뷰 중 추천 비율이며, 리뷰 비중은 같은 구간
        전체 리뷰에서 그 언어가 차지하는 몫입니다.{excludedText} 언어는 리뷰 작성 언어이며 사용자의
        국적이나 거주지를 뜻하지 않습니다.
      </span>
    </p>
  )
}

function LanguageAnalysisSkeleton() {
  return (
    <div aria-busy="true" className="flex flex-col gap-[18px]">
      <div className={`h-[55px] animate-pulse ${panelClass}`} />
      <div className={`flex flex-col gap-sb-2 p-[18px] ${panelClass}`}>
        {Array.from({ length: TOP_LANGUAGE_COUNT }, (_, index) => (
          <div key={index} className="h-[44px] animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
        ))}
      </div>
    </div>
  )
}

function LanguageAnalysisContent({ gameId, data }: { gameId: number; data: LanguageAnalysis }) {
  const { top, rest } = splitByShare(data.languages)

  return (
    <>
      <PeriodBar data={data} />
      <section aria-labelledby="language-share-heading" className={panelClass}>
        <div className="flex flex-wrap items-baseline gap-x-sb-3 gap-y-sb-1 px-[18px] py-[14px]">
          <h2 id="language-share-heading" className="font-medium text-sb-ink">
            언어별 반응 분포
          </h2>
          <p className="text-sb-ink-mute">
            언어 비중 상위 {TOP_LANGUAGE_COUNT}개 언어를 확인할 수 있습니다. 각 언어를 펼치면 대표
            리뷰를 확인할 수 있습니다.
          </p>
        </div>

        {top.length === 0 ? (
          <p className="px-[18px] pb-[18px] text-sb-ink-mute">
            이 구간에 집계된 리뷰가 없습니다. 데이터가 수집되면 언어별 비중이 표시됩니다.
          </p>
        ) : (
          <div className="flex flex-col gap-sb-4 px-[18px] pb-[18px] pt-1.5">
            <div>
              <div
                aria-hidden="true"
                className={`${LANGUAGE_ROW_GRID} hidden py-[10px] pr-[10px] font-sb-mono text-sb-ink-mute md:grid`}
              >
                <span />
                <span>LANGUAGE</span>
                <span>리뷰 비중</span>
                <span>긍정률 · 0–100%</span>
              </div>
              <ul className="flex flex-col">
                {top.map((language) => (
                  <LanguageRow key={language.languageCode} gameId={gameId} language={language} />
                ))}
              </ul>
            </div>
            <SampleNote data={data} excluded={rest} />
          </div>
        )}
      </section>
    </>
  )
}

export default function LanguageAnalysisPage() {
  const gameId = Number(useParams().gameId)
  const query = useLanguageAnalysis(gameId)

  return (
    <div className="mx-auto flex max-w-sb-page flex-col gap-[18px] px-sb-4 pb-sb-8 pt-sb-6 md:px-sb-12">
      {query.isPending ? <LanguageAnalysisSkeleton /> : null}

      {query.isError ? (
        <div role="alert" className={`flex flex-wrap items-center gap-sb-3 p-sb-4 ${panelClass}`}>
          <p className="text-sb-ink">
            {isApiError(query.error)
              ? query.error.message
              : "언어별 분석을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
          </p>
          <Button variant="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
            다시 시도
          </Button>
        </div>
      ) : null}

      {query.isSuccess ? <LanguageAnalysisContent gameId={gameId} data={query.data} /> : null}
    </div>
  )
}
