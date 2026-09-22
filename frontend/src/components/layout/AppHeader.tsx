import { Link, NavLink } from "react-router"
import { BrandLogo } from "@/components/layout/BrandLogo"
import { useLogout, useSession } from "@/hooks/queries/sessionQueries"
import { cn } from "@/lib/cn"
import { paths } from "@/router/paths"

function navLinkClass({ isActive }: { isActive: boolean }) {
  return cn(
    "inline-flex shrink-0 items-center border-b-2 px-sb-3 text-sb-body whitespace-nowrap focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-sb-primary",
    isActive
      ? "border-sb-primary font-medium text-sb-ink"
      : "border-transparent text-sb-ink-mute hover:text-sb-ink",
  )
}

/** 주요 메뉴. 비로그인은 서비스 소개만, 로그인 사용자는 서비스 소개를 제외한 메뉴를 본다. */
function MainNav({ authenticated }: { authenticated: boolean }) {
  return (
    <nav
      aria-label="주요 메뉴"
      className="order-last flex h-sb-control w-full items-stretch overflow-x-auto md:order-none md:ml-sb-3 md:h-full md:w-auto"
    >
      {authenticated ? (
        <>
          <NavLink to={paths.games} className={navLinkClass}>
            게임 탐색
          </NavLink>
          <NavLink to={paths.myGames} className={navLinkClass}>
            내 게임
          </NavLink>
          <NavLink to={paths.plans} className={navLinkClass}>
            기획안 내역
          </NavLink>
        </>
      ) : (
        <NavLink to={paths.home} end className={navLinkClass}>
          서비스 소개
        </NavLink>
      )}
    </nav>
  )
}

function GuestActions() {
  return (
    <div className="flex items-center gap-[6px]">
      <Link
        to={paths.login}
        className="rounded-sb-control px-3 py-2 text-sb-body text-sb-ink hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        로그인
      </Link>
      <Link
        to={paths.signup}
        className="rounded-sb-control bg-sb-primary px-3.5 py-2 text-sb-body font-medium text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        회원가입
      </Link>
    </div>
  )
}

function ChevronRightIcon() {
  return (
    <svg
      aria-hidden
      width="17"
      height="17"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="text-sb-ink-mute"
    >
      <path d="m9 18 6-6-6-6" />
    </svg>
  )
}

function AuthenticatedActions({ nickname }: { nickname: string }) {
  const logout = useLogout()
  const initial = nickname.trim().charAt(0) || "?"

  return (
    <div className="flex items-center gap-2.5">
      <Link
        to={paths.myPage}
        aria-label={`${nickname} 마이페이지`}
        className="inline-flex items-center gap-2 rounded-sb-control border border-sb-hairline-strong py-[5px] pr-2.5 pl-[5px] hover:bg-sb-canvas-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-sb-hairline-strong bg-sb-canvas-soft text-sb-body font-medium text-sb-ink">
          {initial}
        </span>
        <span className="text-sb-body font-medium text-sb-ink">{nickname}</span>
        <ChevronRightIcon />
      </Link>
      <span aria-hidden className="h-3.5 w-px bg-sb-hairline-strong" />
      <button
        type="button"
        onClick={logout}
        className="cursor-pointer rounded-sb-control px-2.5 py-2 text-sb-body text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        로그아웃
      </button>
    </div>
  )
}

function HeaderActionsSkeleton() {
  return (
    <div className="flex min-w-40 items-center justify-end" aria-hidden>
      <div className="h-8 w-36 animate-pulse rounded-sb-control bg-sb-canvas-soft" />
    </div>
  )
}

export function AppHeader() {
  const { data, isPending } = useSession()
  const user = data?.authenticated ? data.user : null

  return (
    <header className="sticky top-0 z-30 border-b border-sb-hairline bg-sb-canvas-header">
      <div className="mx-auto flex max-w-sb-page flex-wrap items-center gap-x-3.5 px-sb-4 md:h-sb-header md:flex-nowrap md:px-sb-12">
        <div className="flex h-sb-header items-center">
          <BrandLogo />
        </div>
        <MainNav authenticated={user !== null} />
        <div className="min-w-0 flex-1" />
        {isPending ? (
          <HeaderActionsSkeleton />
        ) : user ? (
          <AuthenticatedActions nickname={user.nickname} />
        ) : (
          <GuestActions />
        )}
      </div>
    </header>
  )
}
