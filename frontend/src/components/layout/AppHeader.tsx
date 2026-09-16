import { Link } from "react-router"
import { BrandLogo } from "@/components/layout/BrandLogo"
import { useLogout, useSession } from "@/hooks/queries/sessionQueries"
import { paths } from "@/router/paths"

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
      <div className="inline-flex items-center gap-2 rounded-sb-control border border-sb-hairline-strong py-[5px] pr-2.5 pl-[5px]">
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-full border border-sb-hairline-strong bg-sb-canvas-soft text-sb-body font-medium text-sb-ink">
          {initial}
        </span>
        <span className="text-sb-body font-medium text-sb-ink">{nickname}</span>
        <ChevronRightIcon />
      </div>
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
    <header className="border-b border-sb-hairline bg-sb-canvas-header">
      <div className="mx-auto flex h-sb-header max-w-sb-page items-center gap-3.5 px-sb-4 md:px-sb-12">
        <BrandLogo />
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
