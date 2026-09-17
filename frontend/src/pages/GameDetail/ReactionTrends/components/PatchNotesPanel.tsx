import { isApiError } from "@/api/error"
import { usePatchDetail } from "@/hooks/queries/statisticsQueries"

interface PatchNotesPanelProps {
  gameId: number
  patchId: string | null
}

export function PatchNotesPanel({ gameId, patchId }: PatchNotesPanelProps) {
  const query = usePatchDetail(gameId, patchId)
  const isLoading = Boolean(patchId) && query.isFetching && !query.data

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
          <header>
            <h2 className="text-sb-title font-medium text-sb-ink">{query.data.title}</h2>
            <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
              {query.data.patchedOn} · {query.data.publishedAt}
            </p>
          </header>
          <pre className="mt-sb-3 max-h-64 overflow-y-auto whitespace-pre-wrap font-sb-sans text-sb-body leading-relaxed text-sb-ink">
            {query.data.body}
          </pre>
        </>
      ) : null}
    </section>
  )
}
