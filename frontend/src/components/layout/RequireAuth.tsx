import { useEffect, useRef } from "react"
import { Outlet, useLocation, useNavigate } from "react-router"
import { useSession } from "@/hooks/queries/sessionQueries"
import { paths } from "@/router/paths"

/** 로그인이 필요한 라우트를 감싼다. 비로그인이면 안내 후 로그인 페이지로 보내고, 로그인 뒤 원래 페이지로 복귀한다. */
export function RequireAuth() {
  const session = useSession()
  const location = useLocation()
  const navigate = useNavigate()
  const alerted = useRef(false)
  const unauthenticated = session.isSuccess && !session.data.authenticated

  useEffect(() => {
    if (!unauthenticated || alerted.current) return
    alerted.current = true
    alert("로그인 후 이용 가능합니다")
    void navigate(paths.login, {
      replace: true,
      state: { from: location.pathname + location.search },
    })
  }, [unauthenticated, navigate, location.pathname, location.search])

  if (session.isPending || unauthenticated) return null

  if (session.isError) {
    return (
      <main className="mx-auto flex max-w-sb-page flex-col px-sb-4 py-sb-6 md:px-sb-12">
        <div
          role="alert"
          className="flex flex-col items-start gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6"
        >
          <p>세션을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
          <button
            type="button"
            onClick={() => session.refetch()}
            disabled={session.isFetching}
            className="flex h-sb-control cursor-pointer items-center rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          >
            다시 시도
          </button>
        </div>
      </main>
    )
  }

  return <Outlet />
}
