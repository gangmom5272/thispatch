import { useState } from "react"
import { Link } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { TranslationErrorNotice } from "@/components/TranslationErrorNotice"
import { usePatchDetail, usePatchTranslation } from "@/hooks/queries/patchQueries"
import { paths } from "@/router/paths"

const linkClass =
  "rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

interface PatchNotePanelProps {
  gameId: number
  patchId: string
  gameTitle: string
  patchTitle: string
}

/**
 * 사례 상세용 패치노트 패널.
 * 「번역」 선택 시에만 번역 API를 조회하고, 원문은 상세 응답을 쓴다.
 */
export default function PatchNotePanel({
  gameId,
  patchId,
  gameTitle,
  patchTitle,
}: PatchNotePanelProps) {
  // showOriginal: true면 상세 원문, false면 번역 API 결과
  const [showOriginal, setShowOriginal] = useState(true)
  const patch = usePatchDetail(gameId, patchId)
  // wantsTranslation: 번역 탭이 선택된 뒤에만 조회
  const wantsTranslation = !showOriginal
  const translationQuery = usePatchTranslation(patchId, wantsTranslation)
  // showTranslationError: 번역 탭에서만 에러 UI
  const showTranslationError = wantsTranslation && translationQuery.isError

  // displayBody: 토글·로딩에 따른 본문
  const displayBody = (() => {
    if (!patch.data) return ""
    if (showOriginal) return patch.data.body
    if (translationQuery.isPending || !translationQuery.data) return "번역 중…"
    return translationQuery.data.translatedBody
  })()
  // headingLabel: 헤더에 붙는 원문/번역 표시
  const headingLabel = showOriginal ? "원문" : "번역"

  return (
    <section
      aria-labelledby="patch-note-heading"
      className="flex flex-col rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface"
    >
      <div className="flex flex-wrap items-center justify-between gap-x-sb-2 gap-y-sb-2 border-b border-sb-hairline px-sb-4 py-sb-3">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-sb-2 gap-y-sb-1">
          <h2 id="patch-note-heading" className="font-medium">
            패치노트
          </h2>
          <p className="font-sb-mono text-sb-ink-mute">
            {gameTitle}{" "}
            {showOriginal || !translationQuery.data
              ? patchTitle
              : translationQuery.isPending
                ? "번역 중…"
                : translationQuery.data.translatedTitle}{" "}
            · {headingLabel}
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
            {showTranslationError ? (
              <TranslationErrorNotice error={translationQuery.error} />
            ) : displayBody.trim() ? (
              <p className="whitespace-pre-wrap leading-relaxed">{displayBody}</p>
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
