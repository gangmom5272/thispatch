import { Link } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { usePatchDetail } from "@/hooks/queries/patchQueries"
import { paths } from "@/router/paths"

const linkClass =
  "rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

interface PatchNotePanelProps {
  gameId: number
  patchId: string
  gameTitle: string
  patchTitle: string
}

export default function PatchNotePanel({
  gameId,
  patchId,
  gameTitle,
  patchTitle,
}: PatchNotePanelProps) {
  const patch = usePatchDetail(gameId, patchId)

  return (
    <section
      aria-labelledby="patch-note-heading"
      className="flex flex-col rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
    >
      <div className="flex flex-wrap items-baseline gap-x-sb-2 gap-y-sb-1 border-b border-sb-hairline px-sb-4 py-sb-3">
        <h2 id="patch-note-heading" className="font-medium">
          패치노트
        </h2>
        <p className="font-sb-mono text-sb-ink-mute">
          {gameTitle} {patchTitle} · 원문
        </p>
      </div>
      <div className="flex flex-1 flex-col gap-sb-4 p-sb-4">
        {patch.isPending && (
          <p role="status" aria-live="polite" className="text-sb-ink-mute">
            패치노트를 불러오는 중입니다…
          </p>
        )}
        {patch.isError && (
          <div role="alert" className="flex flex-col gap-sb-3">
            <p>
              {isApiError(patch.error)
                ? patch.error.message
                : "패치노트를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
            </p>
            {isApiError(patch.error) && patch.error.status === 401 ? (
              <Link to={paths.login} className={`self-start ${linkClass}`}>
                로그인하기
              </Link>
            ) : (
              <Button
                variant="secondary"
                className="self-start"
                onClick={() => patch.refetch()}
                disabled={patch.isFetching}
              >
                다시 시도
              </Button>
            )}
          </div>
        )}
        {patch.isSuccess && (
          <>
            {patch.data.body.trim() ? (
              <p className="whitespace-pre-wrap leading-relaxed">{patch.data.body}</p>
            ) : (
              <p className="text-sb-ink-mute">패치노트 본문이 비어 있습니다.</p>
            )}
            <a
              href={patch.data.url}
              target="_blank"
              rel="noopener noreferrer"
              className={`mt-auto self-start ${linkClass}`}
            >
              Steam에서 원문 보기 <span aria-hidden="true">↗</span>
            </a>
          </>
        )}
      </div>
    </section>
  )
}
