import { NavLink, useParams } from "react-router"
import {
  GAME_DETAIL_MAIN_TABS,
  GAME_DETAIL_TAB_LABELS,
  GAME_DETAIL_TABS,
  gameDetailTabPath,
} from "@/router/paths"

function tabClassName(isActive: boolean) {
  const base =
    "inline-flex h-[50px] shrink-0 items-center border-b-2 px-0.5 text-sb-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
  if (isActive) {
    return `${base} border-sb-primary font-medium text-sb-ink`
  }
  return `${base} border-transparent text-sb-ink-mute hover:text-sb-ink`
}

function PenLineIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-[18px] shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 20h9" />
      <path d="M16.376 3.622a1 1 0 0 1 3.002 3.002L7.368 18.635a2 2 0 0 1-.855.506l-2.872.838a.5.5 0 0 1-.62-.62l.838-2.872a2 2 0 0 1 .506-.854z" />
    </svg>
  )
}

function planButtonClassName(isActive: boolean) {
  const base =
    "inline-flex h-sb-control shrink-0 items-center gap-[7px] rounded-sb-control bg-sb-primary px-sb-4 text-sb-body font-medium text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary active:bg-sb-primary-deep"
  return isActive
    ? `${base} ring-2 ring-sb-primary-soft ring-offset-2 ring-offset-sb-canvas-surface`
    : base
}

export function GameDetailTabs() {
  const { gameId } = useParams()
  if (!gameId) return null

  return (
    <nav
      aria-label="게임 상세 탭"
      className="flex h-[58px] items-center justify-between gap-sb-4 border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 pt-sb-2 md:px-sb-12"
    >
      <div className="flex min-w-0 gap-sb-4 overflow-x-auto md:gap-[26px]">
        {GAME_DETAIL_MAIN_TABS.map((tab) => (
          <NavLink
            key={tab}
            to={gameDetailTabPath(gameId, tab)}
            className={({ isActive }) => tabClassName(isActive)}
          >
            {GAME_DETAIL_TAB_LABELS[tab]}
          </NavLink>
        ))}
      </div>

      <NavLink
        to={gameDetailTabPath(gameId, GAME_DETAIL_TABS.plan)}
        className={({ isActive }) => planButtonClassName(isActive)}
      >
        <PenLineIcon />
        {GAME_DETAIL_TAB_LABELS[GAME_DETAIL_TABS.plan]}
      </NavLink>
    </nav>
  )
}
