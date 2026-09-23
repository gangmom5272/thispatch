import { Outlet, ScrollRestoration, type Location } from "react-router"
import { AppHeader } from "@/components/layout/AppHeader"
import { paths } from "@/router/paths"

/** 게임 목록은 검색·필터 조합별로 스크롤을 기억해 뒤로가기와 "게임 목록" 링크 모두 같은 위치로 복원한다. */
function scrollKey(location: Location) {
  return location.pathname === paths.games ? location.pathname + location.search : location.key
}

export function AppShell() {
  return (
    <div className="min-h-screen bg-sb-canvas-base font-sb-sans text-sb-body text-sb-ink scheme-dark">
      <AppHeader />
      <Outlet />
      <ScrollRestoration getKey={scrollKey} />
    </div>
  )
}
