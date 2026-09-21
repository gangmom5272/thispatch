import { useState } from "react"
import { TranslationErrorNotice } from "@/components/TranslationErrorNotice"
import { isApiError } from "@/api/error"
import { usePatchTranslation } from "@/hooks/queries/patchQueries"
import { usePatchDetail } from "@/hooks/queries/statisticsQueries"

interface PatchNotesPanelProps {
  gameId: number
  patchId: string | null
}

/**
 * 반응 추세용 패치노트 패널.
 * 「번역」 선택 시에만 번역 API를 조회하고, 원문은 상세 응답을 쓴다.
 */
export function PatchNotesPanel({ gameId, patchId }: PatchNotesPanelProps) {
  // showOriginal: true면 상세 원문, false면 번역 API 결과
  const [showOriginal, setShowOriginal] = useState(true)
  const query = usePatchDetail(gameId, patchId)
  // wantsTranslation: 패치가 있고 번역 탭일 때만 조회
  const wantsTranslation = Boolean(patchId) && !showOriginal
  const translationQuery = usePatchTranslation(patchId ?? "", wantsTranslation)
  const isLoading = Boolean(patchId) && query.isFetching && !query.data
  // showTranslationError: 번역 탭에서만 에러 UI
  const showTranslationError = wantsTranslation && translationQuery.isError

  // displayTitle / displayBody: 토글·로딩에 따른 표시 문자열
  const displayTitle = (() => {
    if (!query.data) return ""
    if (showOriginal) return query.data.title
    if (translationQuery.isPending || !translationQuery.data) return "번역 중…"
    return translationQuery.data.translatedTitle
  })()
  const displayBody = (() => {
    if (!query.data) return ""
    if (showOriginal) return query.data.body
    if (translationQuery.isPending || !translationQuery.data) return "번역 중…"
    return translationQuery.data.translatedBody
  })()

  return (
    <section className="rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4">
      {!patchId ? (
        <p className="text-sb-body text-sb-ink-mute">패치를 선택하면 노트가 표시됩니다.</p>
      ) : null}
      {isLoading ? <p className="text-sb-body text-sb-ink-mute">패치 노트를 불러오는 중…</p> : null}
      {patchId && query.isError ? (
        <p className="text-sb-body text-sb-neg-text">
          {isApiError(query.error) ? query.error.message : "패치를 불러오지 못했습니다."}
        </p>
      ) : null}
      {query.data ? (
        <>
          <header className="flex flex-wrap items-start justify-between gap-sb-3">
            <div className="min-w-0">
              <h2 className="text-sb-title font-medium text-sb-ink">{displayTitle}</h2>
              <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
                {query.data.patchedOn} · {query.data.publishedAt}
              </p>
            </div>
            <div className="flex shrink-0 gap-sb-1">
              <button
                type="button"
                onClick={() => setShowOriginal(false)}
                className={
                  !showOriginal
                    ? "h-sb-control cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-3 text-sb-caption text-sb-ink"
                    : "h-sb-control cursor-pointer rounded-sb-control px-sb-3 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
                }
              >
                번역
              </button>
              <button
                type="button"
                onClick={() => setShowOriginal(true)}
                className={
                  showOriginal
                    ? "h-sb-control cursor-pointer rounded-sb-control bg-sb-canvas-active px-sb-3 text-sb-caption text-sb-ink"
                    : "h-sb-control cursor-pointer rounded-sb-control px-sb-3 text-sb-caption text-sb-ink-mute hover:text-sb-ink"
                }
              >
                원문
              </button>
            </div>
          </header>
          {showTranslationError ? (
            <div className="mt-sb-3">
              <TranslationErrorNotice error={translationQuery.error} />
            </div>
          ) : (
            <pre className="mt-sb-3 max-h-64 overflow-y-auto whitespace-pre-wrap font-sb-sans text-sb-body leading-relaxed text-sb-ink">
              {displayBody}
            </pre>
          )}
        </>
      ) : null}
    </section>
  )
}
