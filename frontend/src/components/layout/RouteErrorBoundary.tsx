import { useEffect } from "react"
import { Link, useRouteError } from "react-router"
import { paths } from "@/router/paths"

const RELOADED_AT_KEY = "thispatch.chunkReloadedAt"
const RELOAD_GUARD_MS = 10_000

/** 배포로 이전 청크 파일이 사라져 페이지 모듈을 받지 못한 경우(브라우저별 메시지) */
function isChunkLoadError(error: unknown): boolean {
  return (
    error instanceof Error &&
    /dynamically imported module|Importing a module script failed/i.test(error.message)
  )
}

/** 직전에 자동 새로고침했는데 또 실패했다면 반복하지 않는다. */
function shouldAutoReload(): boolean {
  try {
    const reloadedAt = Number(sessionStorage.getItem(RELOADED_AT_KEY))
    if (Date.now() - reloadedAt < RELOAD_GUARD_MS) return false
    sessionStorage.setItem(RELOADED_AT_KEY, String(Date.now()))
    return true
  } catch {
    return false
  }
}

export function RouteErrorBoundary() {
  const error = useRouteError()
  const chunkLoadError = isChunkLoadError(error)

  useEffect(() => {
    if (chunkLoadError && shouldAutoReload()) window.location.reload()
  }, [chunkLoadError])

  return (
    <main className="mx-auto flex max-w-sb-page flex-col px-sb-4 py-sb-6 md:px-sb-12">
      <div
        role="alert"
        className="flex flex-col items-start gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6"
      >
        <p>
          {chunkLoadError
            ? "화면을 불러오지 못했습니다. 네트워크 연결을 확인한 뒤 새로고침해 주세요."
            : "화면을 표시하는 중 문제가 발생했습니다. 새로고침하거나 홈으로 이동해 주세요."}
        </p>
        <div className="flex gap-sb-2">
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="flex h-sb-control cursor-pointer items-center rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            새로고침
          </button>
          <Link
            to={paths.home}
            reloadDocument
            className="flex h-sb-control cursor-pointer items-center rounded-sb-control px-sb-4 text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            홈으로
          </Link>
        </div>
      </div>
    </main>
  )
}
