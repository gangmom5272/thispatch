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

  return <Outlet />
}
