import { NavLink, useParams } from "react-router"
import { GAME_DETAIL_TAB_LABELS, GAME_DETAIL_TAB_ORDER, gameDetailTabPath } from "@/router/paths"

function tabClassName(isActive: boolean) {
  const base =
    "inline-flex h-[50px] shrink-0 items-center border-b-2 px-0.5 text-sb-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
  if (isActive) {
    return `${base} border-sb-primary font-medium text-sb-ink`
  }
  return `${base} border-transparent text-sb-ink-mute hover:text-sb-ink`
}

export function GameDetailTabs() {
  const { gameId } = useParams()
  if (!gameId) return null

  return (
    <nav
      aria-label="게임 상세 탭"
      className="flex gap-sb-4 overflow-x-auto border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 md:gap-sb-6 md:px-sb-12"
    >
      {GAME_DETAIL_TAB_ORDER.map((tab) => (
        <NavLink
          key={tab}
          to={gameDetailTabPath(gameId, tab)}
          className={({ isActive }) => tabClassName(isActive)}
        >
          {GAME_DETAIL_TAB_LABELS[tab]}
        </NavLink>
      ))}
    </nav>
  )
}
