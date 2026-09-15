import { useEffect } from "react"
import { isApiError } from "@/api/error"
import { usePatchDetail } from "@/hooks/queries/statisticsQueries"

interface PatchNoteModalProps {
  gameId: number
  patchId: string
  onClose: () => void
}

export function PatchNoteModal({ gameId, patchId, onClose }: PatchNoteModalProps) {
  const query = usePatchDetail(gameId, patchId)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose()
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-sb-scrim p-sb-4"
      role="presentation"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="patch-note-title"
        className="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-sb-modal border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6 shadow-sb-modal"
        onClick={(event) => event.stopPropagation()}
      >
        {query.isPending ? (
          <p className="text-sb-body text-sb-ink-mute">패치 노트를 불러오는 중…</p>
        ) : null}
        {query.isError ? (
          <p className="text-sb-body text-sb-neg-text">
            {isApiError(query.error) ? query.error.message : "패치를 불러오지 못했습니다."}
          </p>
        ) : null}
        {query.data ? (
          <>
            <header className="flex items-start justify-between gap-sb-4">
              <div>
                <h2 id="patch-note-title" className="text-sb-title font-medium text-sb-ink">
                  {query.data.title}
                </h2>
                <p className="mt-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute">
                  {query.data.patchedOn} · {query.data.publishedAt}
                </p>
              </div>
              <button
                type="button"
                onClick={onClose}
                className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
              >
                닫기
              </button>
            </header>
            <pre className="mt-sb-4 whitespace-pre-wrap font-sb-sans text-sb-body leading-relaxed text-sb-ink">
              {query.data.body}
            </pre>
          </>
        ) : null}
      </div>
    </div>
  )
}
